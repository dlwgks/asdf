import json
import os
import re
import time
import urllib.error
import urllib.request

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

OLLAMA_URL = os.environ.get('OLLAMA_URL', 'http://127.0.0.1:11434').rstrip('/')
MODEL_ID = os.environ.get('COACH_MODEL', 'qwen3:4b-instruct')
KEEP_ALIVE = os.environ.get('COACH_KEEP_ALIVE', '30m')
REQUEST_TIMEOUT = float(os.environ.get('COACH_TIMEOUT_SECONDS', '80'))

GUARD = (
    '\n\n[답변 규칙]\n'
    '- 오늘 한 운동은 회원 정보의 "오늘 완료한 운동"에 적힌 것만 말합니다. "없음"이면 아직 하지 않았다고 답합니다.\n'
    '- 오늘 루틴은 계획이며, 완료 여부는 "오늘 완료한 운동"으로만 판단합니다.\n'
    '- 회원 정보에 없는 수치나 사실을 지어내지 않습니다.\n'
    '- 한국어로만, 두세 문장으로 답합니다.'
)

state = {}
app = FastAPI(title='MOVID coach (Ollama)')


class Message(BaseModel):
    role: str
    content: str = Field(max_length=2000)


class ChatRequest(BaseModel):
    system: str = Field(default='', max_length=4000)
    messages: list[Message] = Field(min_length=1, max_length=20)
    max_new_tokens: int = Field(default=120, ge=16, le=600)


def call_ollama(path, payload=None, timeout=REQUEST_TIMEOUT):
    data = None if payload is None else json.dumps(payload).encode('utf-8')
    request = urllib.request.Request(
        OLLAMA_URL + path, data=data, headers={'Content-Type': 'application/json'},
        method='GET' if payload is None else 'POST')
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response)


def trim_to_sentence(text):
    cut = max(text.rfind('.'), text.rfind('!'), text.rfind('?'), text.rfind('\n'))
    return text[:cut + 1].strip() if cut >= len(text) * 0.3 else text.strip()


@app.on_event('startup')
def warm_up():
    started = time.perf_counter()
    try:
        call_ollama('/api/chat', {
            'model': MODEL_ID, 'stream': False, 'think': False, 'keep_alive': KEEP_ALIVE,
            'messages': [{'role': 'user', 'content': '안녕'}], 'options': {'num_predict': 8}})
        state['ready'] = True
        state['load_seconds'] = round(time.perf_counter() - started, 1)
    except (urllib.error.URLError, OSError, ValueError):
        state['ready'] = False


@app.get('/health')
def health():
    try:
        call_ollama('/api/version', timeout=3)
        reachable = True
    except (urllib.error.URLError, OSError, ValueError):
        reachable = False
    if not reachable:
        raise HTTPException(503, detail={'code': 'OLLAMA_DOWN', 'message': 'Ollama 서버에 연결하지 못했어요.'})
    return {'status': 'ok', 'model': MODEL_ID, 'device': 'ollama', 'load_seconds': state.get('load_seconds')}


@app.post('/v1/chat')
def chat(req: ChatRequest):
    system = (req.system + GUARD) if req.system else GUARD.strip()
    messages = [{'role': 'system', 'content': system}] + [
        {'role': m.role, 'content': m.content} for m in req.messages[-4:] if m.role in ('user', 'assistant')]
    started = time.perf_counter()
    try:
        result = call_ollama('/api/chat', {
            'model': MODEL_ID, 'messages': messages, 'stream': False, 'think': False, 'keep_alive': KEEP_ALIVE,
            'options': {'num_predict': req.max_new_tokens, 'temperature': 0.7, 'top_p': 0.8, 'top_k': 20}})
    except urllib.error.HTTPError as exc:
        raise HTTPException(502, detail={'code': 'OLLAMA_ERROR', 'message': f'모델 서버 오류({exc.code})'}) from exc
    except (urllib.error.URLError, OSError) as exc:
        raise HTTPException(503, detail={'code': 'OLLAMA_DOWN', 'message': 'Ollama 서버에 연결하지 못했어요.'}) from exc
    text = re.sub(r'<think>.*?</think>', '', result.get('message', {}).get('content', ''), flags=re.S).strip()
    if result.get('done_reason') == 'length':
        text = trim_to_sentence(text)
    if not text:
        raise HTTPException(502, detail={'code': 'EMPTY_REPLY', 'message': '모델이 빈 답변을 반환했어요.'})
    return {'reply': text, 'model': MODEL_ID, 'seconds': round(time.perf_counter() - started, 1)}

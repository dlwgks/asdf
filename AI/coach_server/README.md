# MOVID 코치 서버 (Ollama)

백엔드(`vitality.coach.server-url`)가 호출하는 코치 서버입니다. 모델은 Ollama가 실행하고, 이 서버는 요청 형식을 맞춰 전달합니다.

## 준비

```bash
brew install ollama
ollama serve
ollama pull qwen3:4b-instruct
```

`qwen3:4b`(태그에 instruct가 없는 모델)는 답변 전에 긴 추론을 먼저 출력하므로 쓰지 않습니다.

## 실행

```bash
cd AI/coach_server
python -m uvicorn coach_server_app:app --host 0.0.0.0 --port 8002
```

필요한 패키지는 `fastapi`, `uvicorn`, `pydantic`입니다. AI 서버 가상환경에 이미 설치되어 있습니다.

## 설정(환경변수)

| 이름 | 기본값 | 설명 |
|---|---|---|
| `OLLAMA_URL` | `http://127.0.0.1:11434` | Ollama 주소 |
| `COACH_MODEL` | `qwen3:4b-instruct` | 사용할 모델 태그 |
| `COACH_KEEP_ALIVE` | `30m` | 모델을 메모리에 유지하는 시간 |
| `COACH_TIMEOUT_SECONDS` | `80` | 모델 응답 대기 시간 |

## API

- `GET /health`: Ollama 연결 확인
- `POST /v1/chat`: `{ "system": "...", "messages": [{"role": "user", "content": "..."}], "max_new_tokens": 120 }` → `{ "reply": "...", "model": "...", "seconds": 0.0 }`

## 알아 둘 점

- Ollama의 속도 이점은 맥(GPU) 기준입니다. GPU가 없는 서버에서는 같은 속도가 나오지 않으므로 배포 환경에서 따로 측정해야 합니다.
- 답변은 이전 대화 4개까지만 사용하고, 길이 제한에 걸리면 문장이 끝나는 곳에서 자릅니다.

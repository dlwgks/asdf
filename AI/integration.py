"""Spring-facing photo analysis. DINOv3 features are not muscle grades."""
from __future__ import annotations
import base64
import io
import math
import os
import threading
import time
from pathlib import Path
import cv2
import numpy as np
from PIL import Image
from analyzers.analyze_body import analyze_image
from analyzers.dinov3_features import DinoFeatureExtractor, upper_arm_box
from analyzers.contour_classifier import ContourClassifier
from measurement_report import measurement_report
from analyzers.photo_geometry import extra_geometry, photo_quality, side_neck_measurement
from analyzers.pose_guard import create_pose_guard
from analyzers.overlay import draw_overlay

class AnalysisFailure(ValueError):
    def __init__(self, code, message):
        self.code, self.message = code, message
        super().__init__(message)

def rounded_measure(value):
    """Missing or degenerate ratios stay unavailable instead of failing JSON output."""
    if value is None:
        return None
    value = float(value)
    return round(value, 2) if math.isfinite(value) else None


def valid_landmark(point):
    return (isinstance(point, dict)
            and all(not isinstance(point.get(k), bool) and isinstance(point.get(k), (int, float))
                    and math.isfinite(point[k]) for k in ('x', 'y', 'visibility'))
            and .5 <= point['visibility'] <= 1
            and 0 <= point['x'] <= 1 and 0 <= point['y'] <= 1)


class AnalysisEngine:
    def __init__(self, model_path, device='cpu', *, pose_guard=None):
        self.lock = threading.Lock()
        self.pose_guard = create_pose_guard() if pose_guard is None else pose_guard
        try:
            self.dino = DinoFeatureExtractor(model_path, device=device, local_only=Path(model_path).exists())
        except Exception as exc:
            import logging
            logging.getLogger(__name__).warning('DINOv3 model unavailable (%s). Photo measurements will work without arm feature extraction.', exc)
            self.dino = None
        classifier_path = os.environ.get('AI_CONTOUR_MODEL')
        self.contour = ContourClassifier(classifier_path) if classifier_path else None

    def close(self):
        if self.pose_guard:
            self.pose_guard.close()

    def _person_check(self, image):
        check = self.pose_guard.inspect(cv2.cvtColor(image, cv2.COLOR_BGR2RGB)) if self.pose_guard else {'status': 'not_configured'}
        if check.get('multiple_people_detected'):
            raise AnalysisFailure('MULTIPLE_PEOPLE', '여러 인물이 검출됐어요. 한 명만 보이는 사진을 사용해 주세요.')
        return check

    def _side_measure(self, path, root):
        if path is None:
            return {'status': 'unavailable', 'value': None, 'reason': 'SIDE_PHOTO_REQUIRED',
                    'description': '옆모습 사진을 추가하면 귀·어깨 연결선의 각도를 측정합니다.'}
        image = cv2.imread(str(path))
        if image is None:
            return {'status': 'unavailable', 'value': None, 'reason': 'INVALID_SIDE_IMAGE'}
        try:
            check = self._person_check(image)
            _, _, result = analyze_image(str(path), str(root), .5)
        except AnalysisFailure as exc:
            return {'status': 'unavailable', 'value': None, 'reason': exc.code}
        except RuntimeError as exc:
            if 'No body pose' not in str(exc):
                raise
            return {'status': 'unavailable', 'value': None, 'reason': 'SIDE_NO_PERSON'}
        measured = side_neck_measurement(result['landmarks'], image.shape[1], image.shape[0])
        measured['person_check'] = check
        return measured

    def analyze(self, image_path, output_dir, *, side_path=None, include_review_previews=False):
        # Serial inference bounds memory and protects MediaPipe/PyTorch shared state.
        with self.lock:
            started = time.perf_counter()
            image = cv2.imread(str(image_path))
            if image is None:
                raise AnalysisFailure('INVALID_IMAGE', 'JPG 또는 PNG 사진을 보내 주세요.')
            blur = float(cv2.Laplacian(cv2.cvtColor(image, cv2.COLOR_BGR2GRAY), cv2.CV_64F).var())
            if blur < 5:
                raise AnalysisFailure('IMAGE_BLURRY', '사진이 흐리거나 단색이에요. 다시 촬영해 주세요.')
            person_check = self._person_check(image)
            try:
                _, _, result = analyze_image(str(image_path), str(output_dir), .5)
            except RuntimeError as exc:
                if 'No body pose' in str(exc):
                    raise AnalysisFailure('NO_PERSON', '사람을 찾지 못했어요. 전신 사진을 사용해 주세요.') from exc
                raise
            lm = result['landmarks']
            required = ['nose','left_shoulder','right_shoulder','left_hip','right_hip','left_ankle','right_ankle']
            if any(not valid_landmark(lm.get(k)) for k in required):
                raise AnalysisFailure('NOT_FULL_BODY', '머리·어깨·골반·발목 추정점을 충분히 확인하지 못했어요. 가림과 촬영 각도를 확인해 주세요.')
            metrics = result['metrics']
            measures = { 'shoulder_tilt_deg': rounded_measure(abs(metrics['shoulder_tilt_deg'])),
                         'pelvis_tilt_deg': rounded_measure(abs(metrics['hip_tilt_deg'])),
                         'neck_forward_deg': None,
                         'shoulder_hip_ratio': rounded_measure(metrics['shoulder_to_hip_ratio']),
                         'torso_leg_ratio': rounded_measure(metrics['torso_leg_ratio']),
                         'unavailable': [{'key':'neck_forward_deg','reason':'SIDE_PHOTO_REQUIRED'}] }
            measures.update(extra_geometry(lm, image.shape[1], image.shape[0]))
            side_dir = Path(output_dir) / 'side'
            side_dir.mkdir(exist_ok=True)
            side_measure = self._side_measure(side_path, side_dir)
            measures['neck_forward_deg'] = side_measure['value']
            measures['side_neck'] = side_measure
            measures['unavailable'] = [] if side_measure['status'] == 'measured' else [
                {'key': 'neck_forward_deg', 'reason': side_measure['reason']}]
            quality = photo_quality(lm, image.shape[1], image.shape[0], blur, person_check)
            features = []
            if self.dino is not None:
                with Image.open(image_path) as pil:
                    for side in ('left','right'):
                        box = upper_arm_box(lm, side, *pil.size)
                        if box is None:
                            features.append({'part': f'{side}_upper_arm','status':'unavailable','reason':'LOW_CONFIDENCE'})
                            continue
                        crop = pil.crop(box).convert('RGB')
                        values, elapsed = self.dino.extract(crop)
                        if any(not np.isfinite(v).all() for v in values.values()):
                            raise RuntimeError('Non-finite DINOv3 features')
                        features.append({'part':f'{side}_upper_arm', 'status':'extracted',
                                         'classification':'not_trained','crop_box':list(box),
                                         'cls':values['cls'].tolist(), 'patch_mean':values['patch_mean'].tolist(),
                                         'patch_shape':list(values['patches'].shape), 'seconds':round(elapsed,4)})
                        if include_review_previews:
                            preview = io.BytesIO()
                            crop.save(preview, format='JPEG', quality=85)
                            features[-1]['preview_image_base64'] = base64.b64encode(preview.getvalue()).decode()
            else:
                features = [{'part': f'{s}_upper_arm', 'status': 'unavailable', 'reason': 'MODEL_NOT_LOADED'} for s in ('left', 'right')]
            if self.contour is not None:
                for part in features:
                    if part['status'] == 'extracted':
                        part['classification'] = 'review_required'
            report = measurement_report(measures, features)
            if self.contour is not None:
                report['capabilities']['contour_classifier'] = 'review_required'
            labels = {'shoulder_tilt_deg':'어깨 기울기','pelvis_tilt_deg':'골반 기울기',
                      'neck_forward_deg':'측면 귀·어깨 각도','shoulder_hip_ratio':'어깨/골반 비율','torso_leg_ratio':'상체/하체 비율'}
            return {'status':'done','measurement_version':'pixel-dino-v2','measures':measures,
                    'balance_score':None,'body_type':None,'photo_quality':quality,
                    'judgements':[{'item':k,'label':label,'value':measures[k],
                                   'unit':'deg' if k.endswith('_deg') else 'ratio',
                                   'level':'unavailable','reason':'PROFILE_NOT_VALIDATED'} for k,label in labels.items()],
                    'goal_comparison':None,'text_summary':report['summary'],
                    'measurement_report':report,
                    'summary':report['summary'],
                    'dinov3':{'model':'facebook/dinov3-vits16-pretrain-lvd1689m','revision':self.dino.revision,
                               'device':self.dino.device,'parts':features},
                    'warnings':['사진 기반 참고 수치이며 의료적 진단이 아닙니다.',
                                '의복과 가림은 자동 판정하지 못합니다. 특징 추출을 근육 등급으로 해석하지 마세요.',
                                '인물 수 검사는 보조 검출이며 정면 여부와 의복은 직접 확인해야 합니다.'],
                    'processing_seconds':round(time.perf_counter()-started,3),
                    'debug_image_mime':'image/jpeg',
                    'debug_image_base64':base64.b64encode(draw_overlay(image, lm)).decode()}

"""Recompute Android-emitted replay poses in Python; never creates coach labels."""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np
from blocking_arm_temporal import window, RECIPE, NAMES
from train_coach_system import normalize


def recompute(record, metadata):
    if record.get('feature_recipe')!=RECIPE or metadata.get('feature_recipe')!=RECIPE:
        raise ValueError('V2 metadata and physical replay record required')
    replay=record['replay_window']; frames=replay['frames']
    images=np.asarray([row['image'] for row in frames],dtype=np.float32)
    worlds=np.asarray([row['world'] for row in frames],dtype=np.float32)
    if images.shape!=(len(frames),33,4) or worlds.shape!=(len(frames),33,3):
        raise ValueError('Expected aligned phone-emitted landmark coordinates and confidences')
    times=np.asarray([row['timestamp_ms'] for row in frames])
    X,quality,arm,_=window(images[:,:,:2],worlds,images[:,:,2],images[:,:,3],np.ones(len(frames),dtype=bool),times)
    if arm!=replay['arm'] or abs(quality-replay['quality'])>1e-5:
        raise ValueError('Observed arm or coverage differs across platforms')
    actual=np.asarray(replay['raw_features'],dtype=np.float32)
    if actual.shape!=X.shape or not np.array_equal(np.isnan(actual),np.isnan(X)):
        raise ValueError('Phone observation masks or feature dimensions differ')
    difference=float(np.max(np.abs(actual[np.isfinite(X)]-X[np.isfinite(X)])))
    if not np.isfinite(difference) or difference>5e-4:
        raise ValueError(f'Phone feature difference exceeds tolerance: {difference}')
    mean=np.array(metadata['normalisation']['mean']); std=np.array(metadata['normalisation']['std'])
    observed=np.array([name not in metadata['disabled_features'] for name in NAMES])
    normalized=normalize(X[None],mean,std,observed)
    phone=np.asarray(replay['normalized_input'],dtype=np.float32)
    if phone.shape!=(224,) or not np.isfinite(phone).all():
        raise ValueError('Invalid phone normalized input')
    normalization_difference=float(np.max(np.abs(phone-normalized.reshape(-1))))
    if normalization_difference>.02:
        raise ValueError(f'Phone normalization difference exceeds tolerance: {normalization_difference}')
    return normalized,dict(max_feature_difference=difference,max_normalized_input_difference=normalization_difference)


def main():
    import tensorflow as tf
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device',type=Path,required=True)
    parser.add_argument('--model-dir',type=Path,required=True)
    parser.add_argument('--out',type=Path,required=True)
    args=parser.parse_args()
    original=args.device.read_bytes(); record=json.loads(original)
    metadata_path=args.model_dir/'form_classifier.json'
    metadata=json.loads(metadata_path.read_text())
    digest=hashlib.sha256((args.model_dir/'form_classifier.tflite').read_bytes()).hexdigest()
    if digest!=record.get('model_sha256') or digest!=metadata['model_sha256']:
        raise ValueError('Phone and desktop model hashes differ')
    if not metadata.get('pose_configuration',{}).get('model_sha256') or record.get('pose_model_sha256')!=metadata['pose_configuration']['model_sha256']:
        raise ValueError('Phone and training pose asset hashes differ')
    if record.get('physical_device') is not True or record.get('pose_running_mode')!='LIVE_STREAM':
        raise ValueError('Actual physical LIVE_STREAM replay required')
    if not {'left_arm','right_arm','occlusion','missing_pose','dropped_frame'}.issubset(record.get('preprocessing_parity_cases',[])):
        raise ValueError('Phone synthetic parity cases incomplete')
    X,checks=recompute(record,metadata)
    interpreter=tf.lite.Interpreter(model_path=str(args.model_dir/'form_classifier.tflite'),num_threads=1)
    interpreter.allocate_tensors()
    interpreter.set_tensor(interpreter.get_input_details()[0]['index'],X); interpreter.invoke()
    expected=interpreter.get_tensor(interpreter.get_output_details()[0]['index'])[0]
    actual=np.asarray(record['replay_window']['probabilities'],dtype=np.float32)
    if actual.shape!=(2,) or not np.isfinite(actual).all() or np.any((actual<0)|(actual>1)) or abs(actual.sum()-1)>1e-4:
        raise ValueError('Invalid phone probabilities')
    probability_difference=float(np.max(np.abs(actual-expected)))
    if probability_difference>.03: raise ValueError('Phone full-path probability parity exceeds tolerance')
    record.update(checks,preprocessing_parity_verified=True,max_probability_difference=probability_difference,
        phone_record_sha256=hashlib.sha256(original).hexdigest(),metadata_sha256=hashlib.sha256(metadata_path.read_bytes()).hexdigest(),
        desktop_replay_probabilities=expected.tolist(),deployment_approved=False,
        independent_accuracy=None,coaching_review_verified=False)
    args.out.write_text(json.dumps(record,indent=2,allow_nan=False))
    print(json.dumps({key:record[key] for key in ('model_sha256','pipeline_p95_ms','max_feature_difference',
        'max_normalized_input_difference','max_probability_difference','preprocessing_parity_verified')},indent=2))

if __name__=='__main__': main()

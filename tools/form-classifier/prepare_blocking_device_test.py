"""Stage a v2 research candidate and public replay in instrumentation assets only."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import numpy as np
from blocking_arm_temporal import RECIPE, PROFILE
from coach_dataset import load_skill
from train_coach_system import normalize


def main():
    import tensorflow as tf
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model-dir',type=Path,required=True)
    parser.add_argument('--data',type=Path,required=True)
    parser.add_argument('--public-clip',type=Path,required=True,help='Reviewed public replay only; never student private media')
    args=parser.parse_args()
    metadata=json.loads((args.model_dir/'form_classifier.json').read_text())
    if metadata['feature_recipe']!=RECIPE or metadata['feature_quality_profile']!=PROFILE:
        raise ValueError('This phone test requires v2 weights; never relabel old weights')
    if metadata['decision_policy']['policy']!='abstain' or metadata['deployment_approved'] is not False:
        raise ValueError('Instrumentation staging is for explicitly unapproved research candidates')
    data=load_skill(args.data,'block',{},'feature_usable_frac',.5)
    mean=np.array(metadata['normalisation']['mean']); std=np.array(metadata['normalisation']['std'])
    observed=np.array([name not in metadata['disabled_features'] for name in data.feature_names])
    interpreter=tf.lite.Interpreter(model_path=str(args.model_dir/'form_classifier.tflite'),num_threads=1)
    interpreter.allocate_tensors(); ins=interpreter.get_input_details()[0]; outs=interpreter.get_output_details()[0]
    probes=[]
    for index,raw in enumerate(data.X):
        X=normalize(raw[None],mean,std,observed)
        interpreter.set_tensor(ins['index'],X); interpreter.invoke()
        probes.append(dict(sample_id=data.rows[index]['sample_id'],normalized_input=X.flatten().tolist(),
            expected_probabilities=interpreter.get_tensor(outs['index'])[0].tolist()))
    target=Path(__file__).resolve().parents[2]/'app/src/androidTest/assets/blocking_candidate'
    target.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(args.model_dir/'form_classifier.tflite',target/'candidate.tflite')
    shutil.copyfile(args.public_clip,target/'public-replay.mp4')
    shutil.copyfile(target.parents[2]/'test/resources/blocking_arm_parity.json',target/'parity.json')
    fixture=dict(deployment_approved=False,decision_policy='abstain',feature_recipe=RECIPE,
        model_sha256=hashlib.sha256((target/'candidate.tflite').read_bytes()).hexdigest(),
        source_video_sha256=hashlib.sha256(args.public_clip.read_bytes()).hexdigest(),
        normalisation=metadata['normalisation'],disabled_features=metadata['disabled_features'],
        feature_names=metadata['feature_names'],probes=probes)
    fixture['pose_model_sha256']=metadata['pose_configuration']['model_sha256']
    (target/'probes.json').write_text(json.dumps(fixture,indent=2))
    print('Prepared test-only public replay and research probes; no main assets changed')

if __name__=='__main__': main()

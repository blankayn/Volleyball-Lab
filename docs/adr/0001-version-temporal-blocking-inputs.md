# Version the temporal blocking input contract

Blocking visible-arm reach/hold uses the explicit `blocking_arm_time_v2` contract:
32 elapsed-time targets, one fixed observed arm, presence/visibility checks and
an observation mask with a 50 ms maximum nearest-pose distance. Frame-index
sampling compressed missing Android pose events and changed movement timing;
interpolation would invent evidence during occlusion. Changing this contract
requires fresh feature extraction, training and evaluation, and the app rejects
v1 weights or mixed recipes. Its scope remains the observable arm cycle; whole
blocking mechanics and the other four skill models require their own validated
contracts and data.

Release metadata also binds the exact pose-model file hash. Matching detector
thresholds alone cannot authorize pairing weights with a different pose extractor.

"""Offline training and export of the SBB disruption remaining-duration model.

Modules:
    data      load every published version of every liveticker incident
    features  feature derivation shared by training, prediction and export
    train     snapshots, chronological split, quantile models, metrics
    predict   load the trained bundle and predict (P50, pessimistic) remaining minutes
    advice    turn a prediction and a detour cost into an advice code
    artifact  dependency-free evaluator of the exported JSON artifact
    export    write duration_model.v1.json and golden_vectors.json
"""

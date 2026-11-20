# App movement checks on public coaching clips

| Skill | Label | Movements | Counted by the app | Mean form score when counted | Refusal reasons |
| --- | --- | --- | --- | --- | --- |
| Serving | correct | 7 | 6 (86%) | 42 | ArmsNotVisible 1 |
| Serving | faulty | 1 | 1 (100%) | 40 |  |
| Serving | drill | 4 | 1 (25%) | 54 | ArmsNotVisible 2, ReachOverhead 1 |
| Serving | not_a_rep | 21 | 0 (0%) | - | TooSmall 13, ReachOverhead 6, ArmsNotVisible 2 |
| Serving | exclude | 7 | 4 (57%) | 36 | ReachOverhead 2, TooSmall 1 |
| Setting | correct | 10 | 10 (100%) | 69 |  |
| Setting | faulty | 1 | 1 (100%) | 78 |  |
| Setting | not_a_rep | 6 | 0 (0%) | - | HandsAboveForehead 3, ArmsNotVisible 3 |
| Setting | exclude | 7 | 6 (86%) | 66 | UseBothHands 1 |
| Spiking | correct | 11 | 11 (100%) | 70 |  |
| Spiking | faulty | 5 | 2 (40%) | 74 | ArmsNotVisible 2, ReachOverhead 1 |
| Spiking | drill | 8 | 7 (88%) | 68 | ArmsNotVisible 1 |
| Spiking | not_a_rep | 9 | 0 (0%) | - | TooSmall 7, ArmsNotVisible 1, ReachOverhead 1 |
| Spiking | exclude | 20 | 10 (50%) | 68 | TooSmall 4, ArmsNotVisible 3, ReachOverhead 3 |
| Blocking | not_a_rep | 13 | 0 (0%) | - | ReachBothHandsHigh 11, TooSmall 2 |
| Blocking | wrong_skill | 19 | 1 (5%) | 82 | ReachBothHandsHigh 11, UseBothHands 7 |
| Blocking | exclude | 21 | 5 (24%) | 82 | ReachBothHandsHigh 10, ArmsNotVisible 3, TooSmall 3 |

## Correct reps by camera view

| Skill | View | Correct reps | Counted |
| --- | --- | --- | --- |
| Serving | Front | 1 | 1 |
| Serving | Side | 1 | 1 |
| Serving | Unknown | 5 | 4 |
| Setting | Side | 9 | 9 |
| Setting | Unknown | 1 | 1 |
| Spiking | Front | 9 | 9 |
| Spiking | Side | 1 | 1 |
| Spiking | Unknown | 1 | 1 |

## Can a fault model be trained?

Needs at least 20 correct and 20 faulty reps from at least 5 different videos per skill.

| Skill | Correct reps (videos) | Faulty reps (videos) | Trainable |
| --- | --- | --- | --- |
| Serving | 7 (4) | 1 (1) | no |
| Setting | 10 (2) | 1 (1) | no |
| Spiking | 11 (2) | 5 (3) | no |
| Blocking | 0 (0) | 0 (0) | no |

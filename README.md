## 🏆 Skill Point Algorithm Bounty
Wynncraft is seeking an optimized **Skill Point allocation algorithm** capable of efficiently validating and maximizing equipment combinations under strict performance constraints.

A bounty reward of **up to 100 in-game shares** will be granted for a successful solution.</br>
Exceptional implementations may qualify for a higher reward.
## 📌 Objective
Design an algorithm that:
- Accepts a given set of equipment items
- Evaluates viable combinations
- Returns the **combination containing the highest number of valid items**
## ⚙️ Requirements
Your solution must be written in **java** so we can evaluate on a real scenario
### Functional Constraints
- Each piece of equipment has **skill point requirements** that must be validated
- Equipment may **add or subtract skill points** when equipped
- Skill points from equipment **must not recursively enable other equipment** (no bootstrapping between items)
### Validation Rules
- A piece of equipment is considered **valid** only if all its requirements are met at the time of evaluation
- The algorithm must determine validity across the full combination, **the order of items should not be relevant**
- In case of a combination tie, the combination with the **highest total given skill points** should win
## 🧑‍💻 Implementation
This repository offers you a base structure similar to our real usage with testing and benchmarking
### Instructions
- Fork this repository and implement your changes under the `com.wynncraft.algorithms` package
  - If necessary for your optimization, you can implement your custom version of a `IPlayer`, if not use our `WynnPlayer`
  - Every equipment has their associated type, you are allowed to use the types to optimize your algorithm further as you find fit
- Ensure your algorithm is registered under `AlgorithmRegistry`
- Open a Pull Request to this repository with your new algorithm so we can evaluate it
- Once we validate your PR it will be merged and be a valid entry in the competition
  - If any further modification to your algorithm is necessary, you can submit another Pull Request
  - Make sure the new version is on a separate class and registered again, even if a single line change
- You are allowed to submit multiple algorithms as you find fit, we will choose the best one to our usage case
## 🧪 Combinatory Test Cases
This repository contains a few combinatory test cases for equipment<br/>
🏆 We are also offering rewards for new introduced test cases that break current algorithms!
### Instructions
- Fork this repository and implement your new test case in `CombinationTests`
- Open a Pull Request to this repository with your new test case
- If your test case breaks current algorithms on ways that weren't similar to already existing tests
  - You will be elegible to an untradable share reward depending on case-to-case

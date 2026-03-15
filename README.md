# Battlecode 2025 – IF2211 Algorithm Strategies by Kanarazu Katsu

This repository contains the implementation of a **Battlecode 2025 bot** developed for the **IF2211 Algorithm Strategies** course assignment.

## Game Description

The main objectives of the game are:

- **Paint more than 70% of the map area**, or
- **Destroy all enemy robots and towers**

The game lasts for a maximum of 2000 rounds. If no team wins within this limit, the winner will be determined using the following tiebreakers:

1. Total painted map area
2. Number of surviving allied towers
3. Total chips owned
4. Total paint owned
5. Number of surviving allied robots
6. If still tied, the winner is chosen randomly



## Project Structure

```
.
├───.gradle/
├── artifacts/
├───build/
├── client/
├── matches/
├── resource/
├── doc/
│   └── laporan.pdf
├── src/
│   ├── mainbot/
│   ├── alternative-bots-1/
│   └── alternative-bots-2/
└── test/
```

- `build.gradle`  
  The Gradle configuration file used to manage the build process, dependencies, and execution of the bot.

- `src/`  
  Contains the main source code of the bots used in the Battlecode game.

- `test/`  
  Contains testing code used to verify that the bot's functions work correctly.

- `client/`  
  Contains the Battlecode client used to run simulations and visualize matches.  
  The executable client can be found in this folder.

- `build/`  
  Contains compiled code and temporary build artifacts generated during the build process.  
  This folder is automatically generated and usually does not need to be modified.

- `matches/`  
  The folder where match replay files are stored after simulations are executed.

- `maps/`  
  The folder used to store custom maps for running Battlecode matches.

- `gradlew`, `gradlew.bat`  
  Gradle Wrapper scripts used to run Gradle commands without installing Gradle manually.

  - `gradlew` → for Linux / macOS
  - `gradlew.bat` → for Windows

- `gradle/`  
  Contains *supporting files for the Gradle Wrapper used by the `gradlew` scripts.  
  This folder typically does not need to be modified.

## Implemented Greedy Strategy 

### 1. Mainbot
Mainbot uses a priority-based greedy state system for Soldiers.  
At every turn, the robot selects the highest priority state whose condition is satisfied. The priority order is:

REFILLING → CONNECTING → COMBAT → BUILD → EXPLORE.

The COMBAT state targets enemy robots and enemy resource towers, while enemy defense towers are ignored to avoid inefficient attacks.

Movement decisions are also greedy. From the eight possible directions, the robot chooses the tile with the highest tile score. The scoring rules are:

- Ally paint: 0  
- Enemy paint: −2  
- Inside enemy tower range: −20  

An additional rotation penalty is applied to discourage unnecessary direction changes and maintain efficient movement. A ring buffer of the last 8 visited positions prevents robots from moving back to recently visited tiles.

Tower construction follows a hybrid strategy. The first four towers follow a fixed order (2 Money Towers followed by 2 Paint Towers) to stabilize early-game resources. After that, tower construction becomes adaptive based on the ratio between money towers and paint towers.

Robot spawning follows a round-robin distribution with the ratio Soldier:Mopper:Splasher = 3:2:1. Early in the game (round < 50), Soldiers are prioritized for rapid map expansion. Moppers are spawned if enemy paint appears near ruins or enemy Soldiers approach allied towers.

For targeting behavior:

- Splasher evaluates all possible splash centers within attack range and selects the location with the highest score, prioritizing enemy territory capture.
- Mopper prioritizes removing enemy paint near ruins to enable faster tower construction.

### 2. alternative-bots-1
alternative-bots-1 uses a greedy strategy similar to Alternative 1 for state priority, movement, splasher targeting, and mopper targeting, but introduces improvements in tower decisions, combat rules, and spawn composition.

Before building resource towers, the bot evaluates whether a Defense Tower should be constructed.  
A defense value is calculated based on the number of existing defense towers multiplied by their buff value. If the defense value is sufficiently high and defense towers represent less than 35% of all towers, a defense tower may be built near enemy areas.

The early tower build order becomes:

2 Money Towers → 1 Paint Tower → 1 Defense Tower.

Defense towers are also prioritized for early upgrades once the team has at least 4000 chips.

Combat behavior is also modified. Unlike Alternative 1, this bot can attack enemy defense towers, but only if certain conditions are met:

- The team owns at least one defense tower
- At least three allied Soldiers are nearby
- Soldier HP is above 80

Robot spawning adapts based on defensive strength. If there are at least two defense towers, the spawn ratio shifts from 3:2 (Soldier:Mopper) to 4:1. When resources are abundant and the defense level is strong, the ratio becomes 3:1:1 (Soldier:Splasher:Mopper).

---
### 3. alternative-bots-2
alternative-bots-2 implements a simpler greedy strategy focused on rapidly building Special Resource Patterns (SRP) to increase passive income.

The main heuristic is to construct SRPs as early as possible and expand them to neighboring positions, creating a chain of resource patterns that generate +3 chips per turn each.

Unlike the previous alternatives, this strategy uses six flat states instead of a priority hierarchy:

EXPLORE, BUILD_RUIN, ATTACK_TOWER, BUILD_SRP, EXPAND_SRP, RETREAT.

During exploration, the bot continuously checks whether the current tile is a valid SRP center. If it is valid and not yet marked, the bot immediately switches to the *BUILD_SRP state. After completing one SRP, it greedily checks the eight surrounding positions and selects the first valid candidate to expand the pattern.

Tower decisions are fully adaptive from the fourth tower onward. If the money-to-paint ratio is below 1.5, the bot builds the tower type that restores the balance. This alternative does not build defense towers.
.
## Program Requirements

Before running this project, ensure the following software is installed:

- **Java Development Kit (JDK) 21**
- **Gradle** (optional, since Gradle Wrapper is included)

## How to Build and Run

### 1. Clone the Repository

```
git clone https://github.com/wafhr/Tubes1_Kanarazu-Katsu.git
cd Tubes1_Kanarazu-Katsu
```

### 2. Build the Project


```
gradlew build
```

### 3. Run the Client

Run the client located in the **client folder**.

### 4. Select the Playing Team

In the **Runner Tab**, select the bot located inside the `src` folder that you want to run.



## Team Members

<table border="1">
<tr>
<th>Name</th>
<th>Student ID</th>
</tr>
<tr>
<td>Wafiq Hibban Robbany</td>
<td>13524016</td>
</tr>
<tr>
<td>An-Dafa Anza Avansyah</td>
<td>13524038</td>
</tr>
<tr>
<td>Wildan Abdurrahman Ghazali</td>
<td>13524054</td>
</tr>
</table>

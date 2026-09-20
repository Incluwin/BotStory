### 0x002C — CLOSE_RANGE_ATTACK

**Purpose:** Client reports a melee attack.

```
Field              Type    Notes
[Skip]             byte[4] Client timestamp
numTargets         byte    Upper nibble = number of mobs hit; lower nibble = number of hits per mob
[byte]             byte    0x5B (constant)
skillLevel         byte    Level of skill used (0 if basic attack)
skillId            int     Skill ID (only if skillLevel > 0)
display            byte    Display animation ID
direction          byte    Attack direction
stance             byte    Stance/animation
speed              byte    Attack speed
[byte]             byte    0x0A (constant)
projectile         int     0 for melee
[Per target:]
  mobOid           int     Object ID of the mob
  [byte]           byte    0
  [Per hit line:]
    damage         int     Damage dealt (or negative for miss/critical indicators)
```

---

### 0x002D — RANGED_ATTACK

**Purpose:** Client reports a ranged attack (bow, crossbow, gun, throwing stars).

Same structure as `CLOSE_RANGE_ATTACK` but with:
- `projectile` field set to the ammo/projectile item ID
- Appended `int` (0) after target list

---

### 0x002E — MAGIC_ATTACK

**Purpose:** Client reports a magic skill attack.

Same structure as `CLOSE_RANGE_ATTACK` but with:
- Appended `int charge` if the skill uses a charge mechanic (e.g., Thunder Charge)

---
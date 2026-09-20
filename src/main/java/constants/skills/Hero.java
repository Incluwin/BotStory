/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package constants.skills;

/**
 * @author BubblesDev
 */
public class Hero {
    // 1h sword + shield build
//    Level 120: 1 Rush (1), 2 Brandish (2)
//    Level 121: 3 Brandish (5)
//    Level 122: 3 Brandish (8)
//    Level 123: 3 Brandish (11)
//    Level 124: 3 Brandish (14)
//    Level 125: 3 Brandish (17)
//    Level 127: 3 Brandish (20)
//    Level 128: 3 Brandish (23)
//    Level 129: 3 Brandish (26)
//    Level 130: 3 Brandish (29)
//    Level 131: 1 Brandish (Max), 2 AC (Advanced Combo) (2)
//    Level 132: 3 AC (5)
//    Level 133: 3 AC (8)
//    Level 134: 3 AC (11)
//    Level 135: 3 AC (14)
//    Level 136: 3 AC (17)
//    Level 137: 3 AC (20)
//    Level 138: 3 AC (23)
//    Level 139: 3 AC (26)
//    Level 140: 3 AC (29)
//    Level 141: 1 AC (Max), 2 Stance (2)
//    Level 142: 3 Stance (5)
//    Level 143: 3 Stance (8)
//    Level 144: 3 Stance (11)
//    Level 145: 3 Stance (14)
//    Level 146: 3 Stance (17)
//    Level 147: 3 Stance (20)
//    Level 148: 3 Stance (23)
//    Level 149: 3 Stance (26)
//    Level 150: 3 Stance (29)
//    Level 151: 1 Stance (MAX), 2 Maple Warrior (2)
//    Level 152: 3 Maple Warrior (5)
//    Level 153: 3 Maple Warrior (8)
//    Level 154: 3 Maple Warrior (11)
//    Level 155: 2 Maple Warrior (13), 1 Hero's Will (1)
//    Level 156: 3 Maple Warrior (16)
//    Level 157: 3 Maple Warrior (19)
//    Level 158: 3 Achilles (3)
//    Level 159: 3 Achilles (6)
//    Level 160: 3 Achilles (9)
//    Level 161: 3 Achilles (12)
//    Level 162: 3 Achilles (15)
//    Level 163: 3 Achilles (18)
//    Level 164: 3 Achilles (21)
//    Level 165: 3 Achilles (24)
//    Level 166: 3 Achilles (27)
//    Level 167: 3 Achilles (Max)
//    Level 168: 3 Hero's Will (4)
//    Level 169: 1 Hero's Will (MAX), 2 Guardian (2)
//    Level 170: 3 Guardian (5)
//    Level 171: 3 Guardian (8)
//    Level 172: 3 Guardian (11)
//    Level 173: 3 Guardian (14)
//    Level 174: 3 Guardian (17)
//    Level 175: 3 Guardian (20)
//    Level 176: 3 Guardian (23)
//    Level 177: 3 Guardian (26)
//    Level 178: 3 Guardian (29)
//    Level 179: 1 Guardian (MAX), 2 Enrage (2)
//    Level 180: 3 Enrage (5)
//    Level 181: 3 Enrage (8)
//    Level 182: 3 Enrage (11)
//    Level 183: 3 Enrage (14)
//    Level 184: 3 Enrage (17)
//    Level 185: 3 Enrage (20)
//    Level 186: 3 Enrage (23)
//    Level 187: 3 Enrage (26)
//    Level 188: 3 Enrage (29)
//    Level 189: 1 Enrage (MAX), 2 Rush (3)
//    Level 190: 3 Rush (6)
//    Level 191: 3 Rush (9)
//    Level 192: 3 Rush (12)
//    Level 193: 3 Rush (15)
//    Level 194: 3 Rush (18)
//    Level 195: 3 Rush (21)
//    Level 196: 3 Rush (24)
//    Level 197: 2 Rush (26), 1 Maple Warrior (20)
//    Level 198: 3 Maple Warrior (23)
//    Level 199: 3 Maple Warrior (26)
//    Level 200: 3 Maple Warrior (29)

    // 2h build
//    This build was suggested by HollyCrapHollyCrap, which gets 1 point in AC to begin with for faster Coma / Panic charge rates, then capitalizes on getting Brandish to a point where it can hit 3 mobs per cast, and then proceeds to max ACA right away for a bigger damage boost than the remaining points into Brandish would provide. Then you return to maxing Brandish and the build doesn't deviate any further. The general idea here is that maxing ACA is more beneficial in overall power and grind speed than finishing Brandish right away.
//
//    Level 120: 1 Rush (1), 1 Brandish (1), 1 AC (Advanced Combo) (1)
//    Level 121: 3 Brandish (4)
//    Level 122: 3 Brandish (7)
//    Level 123: 3 Brandish (10)
//    Level 124: 3 Brandish (13)
//    Level 125: 3 Brandish (16)
//    Level 127: 3 Brandish (19)
//    Level 128: 2 Brandish (21), 1 AC (2)
//    Level 129: 3 AC (5)
//    Level 130: 3 AC (8)
//    Level 131: 3 AC (11)
//    Level 132: 3 AC (14)
//    Level 133: 3 AC (17)
//    Level 134: 3 AC (20)
//    Level 135: 3 AC (23)
//    Level 136: 3 AC (26)
//    Level 137: 3 AC (29)
//    Level 138: 1 AC (MAX), 2 Brandish (23)
//    Level 139: 3 Brandish (26)
//    Level 140: 3 Brandish (29)
//    Level 141: 1 Brandish (Max), 2 Stance (2)
//    Level 142: 3 Stance (5)
//    Level 143: 3 Stance (8)
//    Level 144: 3 Stance (11)
//    Level 145: 3 Stance (14)
//    Level 146: 3 Stance (17)
//    Level 147: 3 Stance (20)
//    Level 148: 3 Stance (23)
//    Level 149: 3 Stance (26)
//    Level 150: 3 Stance (29)
//    Level 151: 1 Stance (MAX), 2 Maple Warrior (2)
//    Level 152: 3 Maple Warrior (5)
//    Level 153: 3 Maple Warrior (8)
//    Level 154: 3 Maple Warrior (11)
//    Level 155: 2 Maple Warrior (13), 1 Hero's Will (1)
//    Level 156: 3 Maple Warrior (16)
//    Level 157: 3 Maple Warrior (19)
//    Level 158: 3 Achilles (3)
//    Level 159: 3 Achilles (6)
//    Level 160: 3 Achilles (9)
//    Level 161: 3 Achilles (12)
//    Level 162: 3 Achilles (15)
//    Level 163: 3 Achilles (18)
//    Level 164: 3 Achilles (21)
//    Level 165: 3 Achilles (24)
//    Level 166: 3 Achilles (27)
//    Level 167: 3 Achilles (Max)
//    Level 168: 3 Hero's Will (4)
//    Level 169: 1 Hero's Will (MAX), 2 Enrage (2)
//    Level 170: 3 Enrage (5)
//    Level 171: 3 Enrage (8)
//    Level 172: 3 Enrage (11)
//    Level 173: 3 Enrage (14)
//    Level 174: 3 Enrage (17)
//    Level 175: 3 Enrage (20)
//    Level 176: 3 Enrage (23)
//    Level 177: 3 Enrage (26)
//    Level 178: 3 Enrage (29)
//    Level 179: 1 Enrage (MAX), 2 Rush (3)
//    Level 180: 3 Rush (6)
//    Level 181: 3 Rush (9)
//    Level 182: 3 Rush (12)
//    Level 183: 3 Rush (15)
//    Level 184: 3 Rush (18)
//    Level 185: 3 Rush (21)
//    Level 186: 3 Rush (24)
//    Level 187: 3 Rush (27)
//    Level 188: 3 Rush (MAX)
//    Level 189: 3 Maple Warrior (22)
//    Level 190: 3 Maple Warrior (25)
//    Level 191: 3 Maple Warrior (28)
//    Level 192: 2 Maple Warrior (MAX), 1 Spare SP
//    Level 193: 3 Spare SP
//    Level 194: 3 Spare SP
//    Level 195: 3 Spare SP
//    Level 196: 3 Spare SP
//    Level 197: 3 Spare SP
//    Level 198: 3 Spare SP
//    Level 199: 3 Spare SP
//    Level 200: 3 Spare SP
    public static final int MAPLE_WARRIOR = 1121000;
    public static final int MONSTER_MAGNET = 1121001;
    public static final int STANCE = 1121002;
    public static final int ADVANCED_COMBO = 1120003;
    public static final int ACHILLES = 1120004;
    public static final int GUARDIAN = 1120005;
    public static final int RUSH = 1121006;
    public static final int ENRAGE = 1121010;
    public static final int HEROS_WILL = 1121011;
    public static final int BRANDISH = 1121008;
}
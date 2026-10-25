/*
    This file is part of the HeavenMS MapleStory Server
    Copyleft (L) 2017 RonanLana

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
/* Moony
	Amoria (680000000)
	Engagement ring NPC.
 */

var status;
var state;

var item;
var mats;
var matQty;
var cost;

var options;

var grooms;     // a bride's companions who may give her the ring (docs/BOT_LORE_COUPLES_PLAN.md)
var groom;      // the one she chose: the box is his to give, and he proposes with it

// the living world, loaded only when a bride asks (never at the top level)
function livingWorld() {
    return Java.type('server.living.LivingWorldHooks');
}

function hasEngagementBox(player) {
    for (var i = 2240000; i <= 2240003; i++) {
        if (player.haveItem(i)) {
            return true;
        }
    }

    return false;
}

function start() {
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode == -1) {
        cm.dispose();
    } else {
        if (mode == 0 && type > 0) {
            cm.dispose();
            return;
        }
        if (mode == 1) {
            status++;
        } else {
            status--;
        }

        if (status == 0) {
            options = ["I want to make a ring.", "I want to discard the ring box I have."];
            cm.sendSimple("I'm #p9201000#, the #bengagement ring maker#k. How can I help you?\r\n\r\n#b" + generateSelectionMenu(options));
        } else if (status == 1) {
            if (selection == 0) {
                if (!cm.isQuestCompleted(100400)) {
                    if (!cm.isQuestStarted(100400)) {
                        state = 0;
                        cm.sendNext("So you want to make a engagement ring, huh? Very well, I can provide one after you receive #rblessings#k from your #b#p9201003##k.");
                    } else {
                        cm.sendOk("Take the blessings from your #b#p9201003##k before trying to craft an engagement ring. They must be waiting for you at home, beyond #rHenesys hunting grounds#k.");
                        cm.dispose();
                    }
                } else {
                    if (hasEngagementBox(cm.getPlayer())) {
                        cm.sendOk("Sorry, you already have an engagement box. I cannot provide you more than one box per time.");
                        cm.dispose();
                        return;
                    }
                    if (cm.getPlayer().getGender() != 0) {
                        if (cm.getPlayer().getLevel() < 50) {
                            cm.sendOk("Sorry, but the ring box is currently available only for males. And a proposal is only made to someone of level 50 or higher.");
                            cm.dispose();
                            return;
                        }
                        grooms = livingWorld().ringBoxGrooms(cm.getPlayer());
                        if (grooms.isEmpty()) {
                            cm.sendOk("Sorry, but the ring box is currently available only for males. Bring the man who will give it to you, and I'll craft it for him.");
                            cm.dispose();
                            return;
                        }

                        state = 3;
                        var names = [];
                        for (var g = 0; g < grooms.size(); g++) {
                            names.push(grooms.get(g).getName());
                        }
                        cm.sendSimple("The ring box is the groom's to give. Who is it for?\r\n\r\n#b" + generateSelectionMenu(names));
                        return;
                    }

                    state = 1;
                    options = ["Moonstone", "Star Gem", "Golden Heart", "Silver Swan"];
                    var selStr = "So, what kind of engagement ring you want me to craft?\r\n\r\n#b" + generateSelectionMenu(options);
                    cm.sendSimple(selStr);
                }
            } else {
                if (hasEngagementBox(cm.getPlayer())) {
                    for (var i = 2240000; i <= 2240003; i++) {
                        cm.removeAll(i);
                    }

                    cm.sendOk("Your ring box has been discarded.");
                } else {
                    cm.sendOk("You have no ring box to discard.");
                }

                cm.dispose();
            }
        } else if (status == 2 && state == 3) {
            groom = grooms.get(selection);
            if (!livingWorld().groomAgrees(cm.getPlayer(), groom)) {
                cm.sendOk("Hmm... it seems #b" + groom.getName() + "#k isn't ready for that yet. Love can't be hurried.");
                cm.dispose();
                return;
            }
            if (hasEngagementBox(groom)) {
                state = 4;
                cm.sendYesNo("#b" + groom.getName() + "#k already carries a ring box. Shall he ask you with it?");
                return;
            }

            status = 1; // the ring menu, then the usual crafting, for him
            state = 1;
            options = ["Moonstone", "Star Gem", "Golden Heart", "Silver Swan"];
            cm.sendSimple("So, what kind of engagement ring shall I craft for #b" + groom.getName() + "#k?\r\n\r\n#b" + generateSelectionMenu(options));
        } else if (status == 3 && state == 4) {
            livingWorld().groomProposes(cm.getPlayer(), groom);
            cm.sendOk("Go on, then. He has something to ask you.");
            cm.dispose();
        } else if (status == 2) {
            if (state == 0) {
                cm.sendOk("Where do they live, you ask? My, it goes way back... you see, I'm a friend of theirs, and I was the one who crafted and personally delivered their engagement ring. They live beyond #rHenesys Hunting Grounds#k, I'm sure you know where it is.");
                cm.startQuest(100400);
                cm.dispose();
            } else {
                var itemSet = [2240000, 2240001, 2240002, 2240003];
                var matSet = [[4011007, 4021007], [4021009, 4021007], [4011006, 4021007], [4011004, 4021007]];
                var matQtySet = [[1, 1], [1, 1], [1, 1], [1, 1]];
                var costSet = [30000, 20000, 10000, 5000];

                item = itemSet[selection];
                mats = matSet[selection];
                matQty = matQtySet[selection];
                cost = costSet[selection];

                var prompt = "Then I'm going to craft you a #b#t" + item + "##k, is that right?";
                prompt += " In that case, I'm going to need specific items from you in order to make it. Make sure you have room in your inventory, though!#b";

                if (mats instanceof Array) {
                    for (var i = 0; i < mats.length; i++) {
                        prompt += "\r\n#i" + mats[i] + "# " + matQty[i] + " #t" + mats[i] + "#";
                    }
                } else {
                    prompt += "\r\n#i" + mats + "# " + matQty + " #t" + mats + "#";
                }

                if (cost > 0) {
                    prompt += "\r\n#i4031138# " + cost + " meso";
                }

                cm.sendYesNo(prompt);
            }
        } else if (status == 3) {
            var complete = true;
            var recvItem = item, recvQty = 1, qty = 1;

            if (groom != null && !groom.canHold(recvItem, recvQty)) {
                cm.sendOk("#b" + groom.getName() + "#k has no room for the ring box. He'll need a free USE slot.");
                cm.dispose();
                return;
            } else if (groom == null && !cm.canHold(recvItem, recvQty)) {
                cm.sendOk("Check your inventory for a free slot first.");
                cm.dispose();
                return;
            } else if (cm.getMeso() < cost * qty) {
                cm.sendOk("I'm sorry but there's a fee for my services. Please bring me the right amount of mesos here before trying to forge a ring.");
                cm.dispose();
                return;
            } else {
                if (mats instanceof Array) {
                    for (var i = 0; complete && i < mats.length; i++) {
                        if (!cm.haveItem(mats[i], matQty[i] * qty)) {
                            complete = false;
                        }
                    }
                } else if (!cm.haveItem(mats, matQty * qty)) {
                    complete = false;
                }
            }

            if (!complete) {
                cm.sendOk("Hm, it seems you're lacking some ingredients for the engagement ring. Please provide them first, will you?");
            } else {
                if (mats instanceof Array) {
                    for (var i = 0; i < mats.length; i++) {
                        cm.gainItem(mats[i], -matQty[i] * qty);
                    }
                } else {
                    cm.gainItem(mats, -matQty * qty);
                }

                if (cost > 0) {
                    cm.gainMeso(-cost * qty);
                }

                if (groom != null) {
                    livingWorld().ringBoxMade(cm.getPlayer(), groom, recvItem); // his to give: he proposes with it
                    cm.sendOk("All done, the engagement ring came out just right. I've given it to #b" + groom.getName() + "#k... I believe he has a question for you.");
                } else {
                    cm.gainItem(recvItem, recvQty);
                    cm.sendOk("All done, the engagement ring came out just right. I wish you a happy engagement.");
                }
            }
            cm.dispose();
        }
    }
}

function generateSelectionMenu(array) {
    var menu = "";
    for (var i = 0; i < array.length; i++) {
        menu += "#L" + i + "#" + array[i] + "#l\r\n";
    }
    return menu;
}
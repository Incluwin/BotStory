/*
    This file is part of the HeavenMS MapleStory Server, commands OdinMS-based
    Copyleft (L) 2016 - 2019 RonanLana

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

/*
   @Author: Arthur L - Refactored command content into modules
*/
package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.Disease;
import client.command.Command;
import client.command.CommandTargetPolicy;
import server.life.BanishInfo;
import server.life.MobSkill;
import server.life.MobSkillFactory;
import server.life.MobSkillType;
import server.maps.MapObject;
import server.maps.MapObjectType;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public class DebuffCommand extends Command {
    {
        setDescription("Put a debuff on all nearby players; GM6: !debuff <state> <companion> for one companion.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length >= 2) {
            debuffCompanion(player, params[0], params[1]);
            return;
        }
        if (params.length < 1) {
            player.yellowMessage("Syntax: !debuff SLOW|SEDUCE|ZOMBIFY|CONFUSE|STUN|POISON|SEAL|DARKNESS|WEAKEN|CURSE");
            return;
        }

        Disease disease = null;
        Optional<MobSkill> skill = Optional.empty();

        switch (params[0].toUpperCase(Locale.ROOT)) {
            case "SLOW" -> {
                disease = Disease.SLOW;
                skill = MobSkillFactory.getMobSkill(MobSkillType.SLOW, 7);
            }
            case "SEDUCE" -> {
                disease = Disease.SEDUCE;
                skill = MobSkillFactory.getMobSkill(MobSkillType.SEDUCE, 7);
            }
            case "ZOMBIFY" -> {
                disease = Disease.ZOMBIFY;
                skill = MobSkillFactory.getMobSkill(MobSkillType.UNDEAD, 1);
            }
            case "CONFUSE" -> {
                disease = Disease.CONFUSE;
                skill = MobSkillFactory.getMobSkill(MobSkillType.REVERSE_INPUT, 2);
            }
            case "STUN" -> {
                disease = Disease.STUN;
                skill = MobSkillFactory.getMobSkill(MobSkillType.STUN, 7);
            }
            case "POISON" -> {
                disease = Disease.POISON;
                skill = MobSkillFactory.getMobSkill(MobSkillType.POISON, 5);
            }
            case "SEAL" -> {
                disease = Disease.SEAL;
                skill = MobSkillFactory.getMobSkill(MobSkillType.SEAL, 1);
            }
            case "DARKNESS" -> {
                disease = Disease.DARKNESS;
                skill = MobSkillFactory.getMobSkill(MobSkillType.DARKNESS, 1);
            }
            case "WEAKEN" -> {
                disease = Disease.WEAKEN;
                skill = MobSkillFactory.getMobSkill(MobSkillType.WEAKNESS, 1);
            }
            case "CURSE" -> {
                disease = Disease.CURSE;
                skill = MobSkillFactory.getMobSkill(MobSkillType.CURSE, 1);
            }
        }

        if (disease == null || skill.isEmpty()) {
            player.yellowMessage("Syntax: !debuff SLOW|SEDUCE|ZOMBIFY|CONFUSE|STUN|POISON|SEAL|DARKNESS|WEAKEN|CURSE");
            return;
        }

        for (MapObject mmo : player.getMap().getMapObjectsInRange(player.getPosition(), 777777.7, Arrays.asList(MapObjectType.PLAYER))) {
            Character chr = (Character) mmo;

            if (chr.getId() != player.getId() && CommandTargetPolicy.includeInHumanCommand(player, chr)) {
                chr.giveDebuff(disease, skill.get());
            }
        }
    }

    /**
     * P12 G5, the GM6 form {@code !debuff <state> <companion>}: puts STUN, SEDUCE or CONFUSE on the named Agent on the
     * GM's map, or BANISHes it to the map's spawn point, as a boss's skill would; nobody else is touched.
     */
    private static void debuffCompanion(Character player, String stateName, String agentName) {
        if (player.gmLevel() < CommandTargetPolicy.AGENT_CONTROL_GM_LEVEL) {
            player.yellowMessage("Only a GM6 may debuff one companion: !debuff STUN|SEDUCE|CONFUSE|BANISH <companion>");
            return;
        }
        Character target = player.getMap().getCharacterByName(agentName);
        if (target == null || !CommandTargetPolicy.isAgent(target)) {
            player.yellowMessage("No companion named " + agentName + " on this map.");
            return;
        }
        String state = stateName.toUpperCase(Locale.ROOT);
        if (state.equals("BANISH")) {
            target.changeMapBanish(new BanishInfo(target.getMapId(), "sp", null));
            player.yellowMessage("Banished " + target.getName() + " to this map's spawn point.");
            return;
        }
        Disease disease = switch (state) {
            case "STUN" -> Disease.STUN;
            case "SEDUCE" -> Disease.SEDUCE;
            case "CONFUSE" -> Disease.CONFUSE;
            default -> null;
        };
        Optional<MobSkill> skill = switch (state) {
            case "STUN" -> MobSkillFactory.getMobSkill(MobSkillType.STUN, 7);
            case "SEDUCE" -> MobSkillFactory.getMobSkill(MobSkillType.SEDUCE, 7);
            case "CONFUSE" -> MobSkillFactory.getMobSkill(MobSkillType.REVERSE_INPUT, 2);
            default -> Optional.empty();
        };
        if (disease == null || skill.isEmpty()) {
            player.yellowMessage("Syntax: !debuff STUN|SEDUCE|CONFUSE|BANISH <companion>");
            return;
        }
        target.giveDebuff(disease, skill.get());
        player.yellowMessage("Gave " + state + " to " + target.getName() + ".");
    }
}

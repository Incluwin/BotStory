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
package client;

import server.StatEffect;
import server.life.Element;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Skill {
    private final int id;
    private final List<StatEffect> effects = new ArrayList<>();
    private Element element;
    private int animationTime;
    private int keydownTime;
    private final int job;
    private int skillType = -1;
    private boolean action;
    private String action0;
    private String action1;
    private final Map<Integer, String> levelActions = new HashMap<>();

    public Skill(int id) {
        this.id = id;
        this.job = id / 10000;
    }

    public int getId() {
        return id;
    }

    public StatEffect getEffect(int level) {
        return effects.get(level - 1);
    }

    public int getMaxLevel() {
        return effects.size();
    }

    public boolean isFourthJob() {
        if (job == 2212) {
            return false;
        }
        if (id == 22170001 || id == 22171003 || id == 22171004 || id == 22181002 || id == 22181003) {
            return true;
        }
        return job % 10 == 2;
    }

    public void setElement(Element elem) {
        element = elem;
    }

    public Element getElement() {
        return element;
    }

    public int getAnimationTime() {
        return animationTime;
    }

    /**
     * How long the skill's keydown animation runs, in milliseconds; 0 for skills without one.
     *
     * <p>Keydown skills (Big Bang, Hurricane, Piercing Arrow, Rapid Fire) are charged by holding
     * the key down, and the client will not send the attack until the charge has played. The
     * server never needed the number because it trusts the client's timing - but a bot has no
     * client, so without it a keydown nuke costs an Agent no more than a basic swing.</p>
     */
    public int getKeydownTime() {
        return keydownTime;
    }

    /**
     * How an attacking summon fights, from Skill.wz {@code summon/attack1}: one attack every
     * {@code cycleMs} (the attack animation), landing {@code hitAfterMs} into it, on up to
     * {@code mobCount} monsters inside {@code box} (relative to the summon, laid out facing left)
     * or, for summons that fly to their targets, within {@code radius} of the summon offset by
     * {@code origin}. The client decides when a summon attacks; a bot has no client.
     */
    public record SummonAttack(int cycleMs, int hitAfterMs, int mobCount, java.awt.Rectangle box, int radius,
                               java.awt.Point origin) {
    }

    private SummonAttack summonAttack;

    /** Null for skills that are not attacking summons (Puppet and Beholder summon but do not attack). */
    public SummonAttack getSummonAttack() {
        return summonAttack;
    }

    public void setSummonAttack(SummonAttack summonAttack) {
        this.summonAttack = summonAttack;
    }

    public void setKeydownTime(int time) {
        keydownTime = time;
    }

    private final Map<String, List<String>> afterimageActions = new HashMap<>();

    /**
     * The body actions Skill.wz draws this skill's own afterimage for, when wielding a weapon of the
     * given afterimage type (the weapon's {@code info/afterImage}, e.g. "gun"); empty when the skill
     * has no afterimage for it.
     *
     * <p>A watching client draws a remote attack's afterimage from the skill's
     * {@code afterimage/<weapon>/<action>} node, and a v83 client exits when that node is missing.
     * Rapid Fire names no action and only draws {@code gun/shot}, so it must be shot as "shot".</p>
     */
    public List<String> getAfterimageActions(String weaponAfterimage) {
        return afterimageActions.getOrDefault(weaponAfterimage, List.of());
    }

    public void setAfterimageActions(String weaponAfterimage, List<String> actions) {
        afterimageActions.put(weaponAfterimage, List.copyOf(actions));
    }

    public void setAnimationTime(int time) {
        animationTime = time;
    }

    public void incAnimationTime(int time) {
        animationTime += time;
    }

    public boolean isBeginnerSkill() {
        return id % 10000000 < 10000;
    }

    public void setSkillType(int skillType) {
        this.skillType = skillType;
    }

    public int getSkillType() {
        return skillType;
    }

    public void setAction(boolean act) {
        action = act;
    }

    public boolean getAction() {
        return action;
    }

    public void setAction0(String action) {
        this.action0 = normalizeAction(action);
    }

    public void setAction1(String action) {
        this.action1 = normalizeAction(action);
    }

    public void addLevelAction(int level, String action) {
        String normalizedAction = normalizeAction(action);
        if (level <= 0 || normalizedAction == null) {
            return;
        }
        levelActions.put(level, normalizedAction);
    }

    public String resolveAnimationAction(int skillLevel, boolean twoHanded) {
        if (!levelActions.isEmpty()) {
            String levelAction = levelActions.get(skillLevel);
            if (levelAction != null) {
                return levelAction;
            }
        }

        if (action0 == null) {
            return null;
        }

        if (action1 != null) {
            return twoHanded ? action1 : action0;
        }

        return action0;
    }

    public void addLevelEffect(StatEffect effect) {
        effects.add(effect);
    }

    private static String normalizeAction(String action) {
        if (action == null || action.isBlank()) {
            return null;
        }
        return action;
    }
}

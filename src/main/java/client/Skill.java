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

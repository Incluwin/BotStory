package server;

import client.Job;

import java.util.Map;

final class EquipRequirementChecker {
    private EquipRequirementChecker() {
    }

    static boolean meetsRequirements(Job job, Map<String, Integer> stats, int level, int reqLevel,
                                     int dex, int str, int int_, int luk, int fame) {
        if (stats == null) {
            return false;
        }
        // FriendServer equipment policy: no job gate, and only the class's PRIMARY stat is
        // enforced (STR/INT/DEX/LUK for Warrior/Magician/Bowman/Thief). Pirates and Beginners
        // have no stat gate; secondary stat requirements are ignored across the board.
        // Agents plan gear through this same method, so they follow the server rule exactly.
        if (reqLevel > level) {
            return false;
        }
        if (failsPrimaryStatReq(job, stats, dex, str, int_, luk)) {
            return false;
        }
        int reqPOP = stats.get("reqPOP");
        return reqPOP <= 0 || reqPOP <= fame;
    }

    static boolean failsPrimaryStatReq(Job job, Map<String, Integer> stats, int dex, int str, int int_, int luk) {
        if (job == null) {
            return false;
        }
        return switch (job.getJobNiche()) {
            case 1 -> stats.get("reqSTR") > str;
            case 2 -> stats.get("reqINT") > int_;
            case 3 -> stats.get("reqDEX") > dex;
            case 4 -> stats.get("reqLUK") > luk;
            default -> false;
        };
    }

    /**
     * Bot-only job gate. Players keep the FriendServer "no job gate" rule above, but a bot wearing
     * another class's gear (a Hero in a pirate hat and an archer overall) breaks the world's
     * believability, so every autonomous character is held to the item's reqJob mask.
     */
    static boolean matchesReqJobForBot(Job job, Map<String, Integer> stats) {
        return stats == null || matchesReqJob(job, stats.getOrDefault("reqJob", 0));
    }

    static boolean matchesReqJob(Job job, int reqJob) {
        if (reqJob == 0) {
            return true;
        }

        int jobMask = getJobMask(job);
        return jobMask != 0 && (reqJob & jobMask) != 0;
    }

    static int getJobMask(Job job) {
        if (job == null) {
            return 0;
        }

        return switch (job.getJobNiche()) {
            case 1 -> 0x1;
            case 2 -> 0x2;
            case 3 -> 0x4;
            case 4 -> 0x8;
            case 5 -> 0x10;
            default -> 0;
        };
    }
}

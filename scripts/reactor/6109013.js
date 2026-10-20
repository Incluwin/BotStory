var fid = "glpq_s";
var cleared = 777;

// CWKPQ stage 4 stirge plates (stirge0 / stirge1 share the count): five players standing on them at once clear the
// Blood Stirges. Fixed 2026-10-20 (CWKPQ plan Question 4 (b)): the stock script called killAllMonsters(true), which
// MapleMap does not have (a GraalJS arity error), then used an eim it never had, and fired only on the touch after
// the fifth.
function action(eim) {
    rm.mapMessage(6, "All stirges have disappeared.");
    rm.getMap().killAllMonsters();
    eim.setIntProperty(fid, cleared);
}

function touch() {
    var eim = rm.getEventInstance();
    if (eim == null || eim.getIntProperty(fid) == cleared) {
        return;
    }
    eim.setIntProperty(fid, eim.getIntProperty(fid) + 1);
    if (eim.getIntProperty(fid) >= 5) {
        action(eim);
    }
}

function untouch() {
    var eim = rm.getEventInstance();
    if (eim == null || eim.getIntProperty(fid) == cleared) {
        return;
    }
    eim.setIntProperty(fid, Math.max(0, eim.getIntProperty(fid) - 1));
}

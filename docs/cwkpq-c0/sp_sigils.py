import json, sys, math
SIGIL = {1: [1111008, 1211002, 1311003, 1311004], 3: [3111005, 3111006, 3211005, 3211006, 3221001],
         2: [2001005, 2111002, 2211002, 2321003], 4: [4111002, 4111005, 4211001, 4211004, 4211006],
         5: [5001001, 5111005, 5121001, 5211002, 5221004]}
d = json.load(open(sys.argv[1], encoding='utf-8'))
names = {int(k): v['name'] for k, v in d['skills'].items()}
byid = {p['profileId']: p for p in d['profiles']}
def walk(p):
    """Yield (skillId, points, cumulative SP before) in allocation order."""
    spent = 0
    if p.get('segments'):
        for s in p['segments']:
            yield s['skillId'], s['points'], spent, s['minimumLevel']
            spent += s['points']
    else:
        for lp in p.get('levels', []):
            for a in lp['allocations']:
                yield a['skillId'], a['points'], spent, lp['level']
                spent += a['points']
for p in d['profiles']:
    job = p.get('exactJobId')
    if job is None or job % 10 == 0 and job % 100 != 0 or job % 100 == 0:
        if not (job and job % 100 == 0):
            continue
    arche = job // 100 % 10
    sig = SIGIL.get(arche, [])
    inh = {int(k): v for k, v in p.get('inheritedSkillLevels', {}).items()}
    got = []
    for k in sig:
        if inh.get(k, 0) > 0:
            got.append(f"{names.get(k, k)} {k} inherited {inh[k]}")
    first = None
    for sid, pts, before, minlvl in walk(p):
        if sid in sig and pts > 0:
            start = p.get('startingLevel', 0); entry = p.get('entrySp', 0)
            need = before + 1 - entry
            lvl = start + max(0, math.ceil(need / 3))
            got.append(f"{names.get(sid, sid)} {sid} +{pts} (SP #{before + 1} of the job, ~Lv{max(lvl, minlvl)})")
    dump = [names.get(x, x) for x in p.get('dumpSkillIds', []) if x in sig]
    if dump: got.append('dump: ' + ', '.join(map(str, dump)))
    print(f"{job} {p['profileId']} (Lv{p.get('startingLevel')}-{p.get('supportedThroughLevel')}): " + ('; '.join(got) if got else 'NO sigil skill'))

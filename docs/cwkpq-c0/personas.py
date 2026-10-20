import sys, re, glob, os
rows = []
for f in sorted(glob.glob(os.path.join(sys.argv[1], 'scripts/config/living-world/personas/*.yaml'))):
    cur = None
    for n, line in enumerate(open(f, encoding='utf-8'), 1):
        m = re.match(r'^- id: (\S+)', line)
        if m:
            cur = {'id': m.group(1), 'file': os.path.basename(f), 'line': n}
            rows.append(cur); continue
        if cur is None: continue
        m = re.match(r'^  (job|level|recruitable|strength): (.*)$', line)
        if m: cur[m.group(1)] = m.group(2).strip()
for r in rows:
    if r.get('job', 'BEGINNER') != 'BEGINNER':
        print(r['file'], r['line'], r['id'], r.get('job'), r.get('level'), 'recruitable=' + r.get('recruitable', 'false'))

#!/bin/sh
# Read-only verification. Run with sudo on cloudserv; never prints RADIUS secrets.
set -eu
radius_dir=/etc/freeradius/3.0
if [ ! -d "$radius_dir" ]; then echo "FreeRADIUS 3 configuration not found"; exit 1; fi
if [ ! -e "$radius_dir/mods-enabled/expiration" ]; then
  echo "FAIL: expiration module is not enabled"; exit 1
fi
python3 - "$radius_dir/sites-enabled" <<'PY'
from pathlib import Path
import re,sys
checked=0
for site in Path(sys.argv[1]).iterdir():
    if not site.is_file(): continue
    text=site.read_text()
    # Strip comments before looking for the normal FreeRADIUS authorize section.
    text=re.sub(r'#.*', '', text)
    match=re.search(r'\bauthorize\s*\{',text)
    if not match: continue
    start=match.end(); level=1; end=start
    while end<len(text) and level:
        level += (text[end]=='{')-(text[end]=='}'); end+=1
    section=text[start:end-1]
    sql=re.search(r'^\s*-?sql\s*$',section,re.M)
    expiry=re.search(r'^\s*expiration\s*$',section,re.M)
    if sql:
        checked+=1
        if not expiry or expiry.start()<sql.start():
            raise SystemExit('FAIL: '+site.name+' must call expiration after sql in authorize')
        print('PASS: '+site.name+' reads SQL attributes before checking Expiration')
if not checked: raise SystemExit('FAIL: no SQL-backed authorize section found; manual review required')
PY
if [ "$(timedatectl show -p Timezone --value)" != "Etc/UTC" ] && [ "$(timedatectl show -p Timezone --value)" != "UTC" ]; then
  echo "FAIL: cloud core must use UTC for the generated Expiration dates"; exit 1
fi
# Configuration validation is kept private because debug output can include SQL credentials.
check_log=$(mktemp)
trap 'rm -f "$check_log"' EXIT
chmod 600 "$check_log"
if ! /usr/sbin/freeradius -XC >"$check_log" 2>&1; then
  echo "FAIL: FreeRADIUS configuration validation failed; inspect locally as root"; exit 1
fi
printf '%s\n' 'PASS: FreeRADIUS configuration valid; UTC voucher-expiry integration verified.'

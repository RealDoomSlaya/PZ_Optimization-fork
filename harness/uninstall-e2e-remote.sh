#!/bin/bash
# harness/uninstall-e2e-remote.sh <flip|mac> <checkout> - run harness/uninstall-e2e.sh on a laptop from a desktop queue
# cmd job (2026-09-27): the laptop's own queue must be idle (the script refuses a running game as well). Copies the
# checkout's build/classes, its install.sh and the Workshop mod folder staged from them (42/mod.info, install.bash,
# install.ps1, the install helper Lua from src/workshop/42/, pzopt-classes/ = build/classes + pzopt-files.txt) to
# ~/pzopt-e2e/ there, runs the e2e over ssh and collects its out-* folder to harness/runs/<m>-uninstall-e2e-<ts>/.
set -euo pipefail
m="${1:?machine}"; wt="${2:?checkout with build/classes}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
conf="$REPO/harness/queue/machines.conf"
mcfg() { awk -v s="[$m]" -v k="$1" '$0==s {f=1; next} /^\[/ {f=0} f && index($0, k"=")==1 {print substr($0, length(k)+2)}' "$conf"; }
host=$(mcfg host); key=$(mcfg key); key="${key/#\~/$HOME}"
[[ -n "$host" ]] || { echo "no host for $m in $conf" >&2; exit 2; }
[[ -d "$wt/build/classes/pzopt" ]] || { echo "no $wt/build/classes" >&2; exit 2; }
state=$("$REPO/harness/queue.sh" machines | awk -v m="$m" '$1==m {print $2, $5, $6}')   # machine state since(date time) queued running
[[ "$state" == "connected 0 -" ]] || { echo "$m is not idle in the queue ($state); resubmit later" >&2; exit 75; }

stage=$(mktemp -d "$HOME/.cache/pzopt-e2e-stage.XXXXXX")
trap 'rm -rf "$stage"' EXIT
mod="$stage/item/42"
mkdir -p "$mod/pzopt-classes"
cp -R "$wt/build/classes/." "$mod/pzopt-classes/"
( cd "$mod/pzopt-classes" && { find . -type f | sed 's|^\./||' | grep -v '^pzopt-files.txt$' | sort; echo pzopt-files.txt; } > pzopt-files.txt )
cp -R "$wt/src/workshop/42/." "$mod/"
cp "$wt/install.sh" "$mod/install.bash"; cp "$wt/install.ps1" "$mod/install.ps1"
rev=$(sed -n 's/^revision=//p' "$wt/build/classes/pzopt/build-info.properties")
printf 'name=PZ_Optimization (e2e)\nid=PZ_Optimization\nmodversion=e2e\nversionMin=42.20.0\ndescription=install helper e2e, revision %s\n' "$rev" > "$mod/mod.info"
cp "$wt/install.sh" "$stage/install.sh"
cp "$REPO/harness/uninstall-e2e.sh" "$stage/uninstall-e2e.sh"

ssh_opts=(-i "$key" -o BatchMode=yes -o ServerAliveInterval=5 -o ServerAliveCountMax=3)
echo "staging on $m ($host)"
ssh "${ssh_opts[@]}" "$host" 'bash -c "rm -rf ~/pzopt-e2e/classes ~/pzopt-e2e/item; mkdir -p ~/pzopt-e2e"'
rsync -a -e "ssh ${ssh_opts[*]}" "$wt/build/classes/" "$host:pzopt-e2e/classes/"
rsync -a -e "ssh ${ssh_opts[*]}" "$stage/item/" "$host:pzopt-e2e/item/"
rsync -a -e "ssh ${ssh_opts[*]}" "$stage/install.sh" "$stage/uninstall-e2e.sh" "$host:pzopt-e2e/"
set +e
ssh "${ssh_opts[@]}" "$host" 'bash ~/pzopt-e2e/uninstall-e2e.sh'
rc=$?
set -e
out=$(ssh "${ssh_opts[@]}" "$host" 'bash -c "command ls -td ~/pzopt-e2e/out-* | head -1"' | tr -d '\r')
dest="$REPO/harness/runs/$m-uninstall-e2e-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$dest"
[[ -n "$out" ]] && rsync -a --exclude backup/ -e "ssh ${ssh_opts[*]}" "$host:$out/" "$dest/"
echo "collected: $dest (e2e exit $rc)"
exit $rc

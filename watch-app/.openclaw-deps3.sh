#!/bin/bash
reextract() {
  local pkg="$1"
  local url=$(npm view "$pkg" dist.tarball --registry=https://registry.npmjs.org/ 2>/dev/null)
  [ -z "$url" ] && { echo "NO_URL $pkg"; return 1; }
  curl -sL --max-time 90 -o /tmp/pkg.tgz "$url" || return 1
  local dest="node_modules/$pkg"
  rm -rf "$dest"; mkdir -p "$dest"
  python3 -c "
import tarfile
t = tarfile.open('/tmp/pkg.tgz')
for m in t.getmembers():
    parts = m.name.split('/', 1)
    m.name = parts[1] if len(parts) > 1 else ''
    if not m.name: continue
    m.mode |= 0o200
    t.extract(m, '$dest', filter='data')
t.close()
"
  echo "REEXTRACTED $pkg"
}
rootpkg() {
  local m="$1"
  if [[ "$m" == @* ]]; then echo "$m" | awk -F/ '{print $1"/"$2}';
  else echo "$m" | awk -F/ '{print $1}'; fi
}
for i in $(seq 1 30); do
  out=$(npm run build 2>&1)
  mod=""
  if echo "$out" | grep -q "Cannot find module"; then
    mod=$(echo "$out" | grep -m1 "Cannot find module" | sed "s/.*Cannot find module '\([^']*\)'.*/\1/")
  elif echo "$out" | grep -q "Cannot find package"; then
    mod=$(echo "$out" | grep -m1 "Cannot find package" | sed "s/.*Cannot find package '\([^']*\)'.*/\1/")
  fi
  if [ -n "$mod" ]; then
    mod=$(echo "$mod" | sed "s|.*node_modules/||")  # 绝对路径→相对包路径
    if [[ "$mod" == .* ]]; then
      stack=$(echo "$out" | grep -A4 "Require stack" | grep -m1 "node_modules")
      relpath=$(echo "$stack" | sed "s|.*node_modules/||;s|['\"]||g;s|/$||")
      pkg=$(rootpkg "$relpath")
    else
      pkg=$(rootpkg "$mod")
    fi
    echo "BROKEN[$i]: $mod  ==>  package: $pkg"
    reextract "$pkg" || exit 1
  else
    echo "$out" | tail -5
    echo "===BUILD_LOOP_END==="
    break
  fi
done

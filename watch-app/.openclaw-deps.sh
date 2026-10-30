#!/bin/bash
reextract() { # $1=pkg名(相对node_modules) $2=目标目录
  local pkg="$1" dest="$2"
  local url=$(npm view "$pkg" dist.tarball --registry=https://registry.npmjs.org/ 2>/dev/null)
  [ -z "$url" ] && { echo "NO_URL $pkg"; return 1; }
  curl -sL --max-time 90 -o /tmp/pkg.tgz "$url" || return 1
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
for i in $(seq 1 25); do
  out=$(npm run build 2>&1)
  if echo "$out" | grep -q "Cannot find module"; then
    mod=$(echo "$out" | grep -m1 "Cannot find module" | sed "s/.*Cannot find module '\([^']*\)'.*/\1/")
    stack=$(echo "$out" | grep -A3 "Require stack" | grep -m1 "node_modules")
    if [[ "$mod" == .* ]]; then
      # 相对引用：定位所属包
      relpath=$(echo "$stack" | sed "s|.*node_modules/||;s|['\"]||g;s|/$||")
       awk -F/ .{print if $1 ~ /^@/ print $1"/"$2; else print $1}.)| awk -F/ '{if ($1 ~ /^@/) print $1"/"$2; else print $1}')
      depth="node_modules/$pkg"
      echo "BROKEN_REL $mod in $pkg"
      reextract "$pkg" "$depth" || exit 1
    else
      if echo "$stack" | grep -q "shared-utils/node_modules"; then depth="node_modules/@aiot-toolkit/shared-utils/node_modules/$mod";
      else depth="node_modules/$mod"; fi
      echo "MISSING_PKG $mod -> $depth"
      reextract "$mod" "$depth" || exit 1
    fi
  else
    echo "$out" | tail -5
    echo "===BUILD_LOOP_END==="
    break
  fi
done

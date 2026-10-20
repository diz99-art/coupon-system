#!/usr/bin/env bash
# 인증 서버에 로그인해 액세스 토큰만 출력한다 (15분 유효).
# 사용: TOKEN=$(bench/token.sh <이메일> <비밀번호>)    인증 서버 주소: AUTH_URL (기본 k3s 클러스터)
# 관리자 비밀번호는 platform-infra의 vault(vault_auth_admin_password)에 있다
set -euo pipefail
AUTH_URL="${AUTH_URL:-http://192.168.252.2/auth}"
curl -sf -X POST "$AUTH_URL/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$1\",\"password\":\"$2\"}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])'

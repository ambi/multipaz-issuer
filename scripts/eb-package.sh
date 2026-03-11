#!/usr/bin/env bash
# Creates AWS Elastic Beanstalk deployment ZIP for issuer or verifier.
# Usage: ./scripts/eb-package.sh [issuer|verifier]
# Platform: 64bit Amazon Linux 2023 v4.x.x running Corretto 21

set -euo pipefail

APP="${1:-issuer}"
DIST_DIR="deploy-dist"
TIMESTAMP="$(date +%Y%m%d%H%M%S)"
ZIPFILE="${DIST_DIR}/eb-${APP}-${TIMESTAMP}.zip"

if [[ "$APP" != "issuer" && "$APP" != "verifier" ]]; then
  echo "Usage: $0 [issuer|verifier]"
  exit 1
fi

echo "==> Building :${APP}:installDist ..."
./gradlew ":${APP}:installDist" --no-daemon -q

INSTALL_DIR="${APP}/build/install/${APP}"
if [[ ! -d "$INSTALL_DIR" ]]; then
  echo "Build output not found: $INSTALL_DIR"
  exit 1
fi

echo "==> Packaging ${ZIPFILE} ..."
mkdir -p "$DIST_DIR"

TMPDIR="$(mktemp -d)"
trap "rm -rf '$TMPDIR'" EXIT

# Copy installDist output (bin/ + lib/)
cp -r "${INSTALL_DIR}/." "$TMPDIR/"

# Copy Procfile and .ebextensions
cp "deploy/${APP}/Procfile" "$TMPDIR/Procfile"
cp -r "deploy/${APP}/.ebextensions" "$TMPDIR/.ebextensions"

(cd "$TMPDIR" && zip -r "${OLDPWD}/${ZIPFILE}" . -x "*.bat")

echo ""
echo "Done: ${ZIPFILE}"
echo ""
echo "Next steps:"
echo "  1. Set BASE_URL and secrets in EB console (or eb setenv)"
if [[ "$APP" == "issuer" ]]; then
echo "     eb setenv ENTRA_TENANT_ID=xxx ENTRA_CLIENT_ID=xxx ENTRA_CLIENT_SECRET=xxx"
fi
echo "  2. eb deploy --label ${APP}-${TIMESTAMP} ${ZIPFILE}"
echo "     or upload via AWS console: Elastic Beanstalk > Upload and Deploy"

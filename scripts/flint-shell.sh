#!/bin/bash
# GNU Make 3.81 calls this for recipes AND $(shell ...); preserve argv/exit status.
FLINT_ENV_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
export FLINT_ENV_ROOT
. "$FLINT_ENV_ROOT/scripts/flint-env.sh"
exec /bin/bash "$@"

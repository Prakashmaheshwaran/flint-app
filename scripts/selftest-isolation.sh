# Sourced only by the shell selftests after they create their disposable WORK.
# Isolate fixture creation too, including the first git init/commit.
for flint_test_var in $(compgen -v GIT_) $(compgen -v XDG_); do unset "$flint_test_var"; done
export HOME="$WORK/isolated-home"
export XDG_CONFIG_HOME="$HOME/config" XDG_CACHE_HOME="$HOME/cache" XDG_DATA_HOME="$HOME/data"
export XDG_STATE_HOME="$HOME/state" XDG_RUNTIME_DIR="$HOME/runtime"
mkdir -p "$XDG_CONFIG_HOME" "$XDG_CACHE_HOME" "$XDG_DATA_HOME" "$XDG_STATE_HOME" "$XDG_RUNTIME_DIR"
chmod 700 "$XDG_RUNTIME_DIR"
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=core.hooksPath GIT_CONFIG_VALUE_0=/dev/null
export PYTHONDONTWRITEBYTECODE=1
ulimit -c 0
ulimit -f 1024
unset flint_test_var

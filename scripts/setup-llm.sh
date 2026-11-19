#!/usr/bin/env bash
# Creates ~/.van-gogh/llm.conf for macOS and Linux.
#
#   scripts/setup-llm.sh                 # prompts for the key, input hidden
#   scripts/setup-llm.sh sk-or-v1-...    # non-interactive (CI, demo prep)
#   VANGOGH_LLM_KEY=... scripts/setup-llm.sh
#
# Re-running replaces the file. The key never enters the repo: the script
# writes outside the working tree with 0600 permissions.
set -euo pipefail

conf_dir="${HOME}/.van-gogh"
conf="${conf_dir}/llm.conf"

key="${1:-${VANGOGH_LLM_KEY:-}}"
if [ -z "${key}" ] && [ -t 0 ]; then
    printf 'OpenRouter API key (input hidden): '
    IFS= read -rs key
    printf '\n'
elif [ -z "${key}" ]; then
    IFS= read -r key          # piped input: echo "$KEY" | scripts/setup-llm.sh
fi

# A pasted key often carries a trailing newline or space.
key="$(printf '%s' "${key}" | tr -d '[:space:]')"

if [ -z "${key}" ]; then
    echo "No API key given — nothing written." >&2
    exit 1
fi

umask 077
mkdir -p "${conf_dir}"

cat > "${conf}" <<EOF
# VanGogh LLM settings — created $(date +%Y-%m-%d). Env variables override these.
apiKey = ${key}
# baseUrl = https://openrouter.ai/api/v1
# model   = baidu/ernie-5.5-flash-preview:free
# timeoutSeconds = 60
# titles = 10
EOF

chmod 600 "${conf}"

echo "Wrote ${conf}"
echo "Restart VanGogh, press AI in the search bar, and search a title or a vibe."

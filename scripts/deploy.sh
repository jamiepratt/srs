#!/usr/bin/env bash
# Normal release path: schema first, then the committed static build on Pages.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ $(git branch --show-current) != main ]]; then
  echo 'Switch to main after integrating and committing the tested build.' >&2
  exit 1
fi
if ! git diff HEAD --quiet -- src docs scripts supabase test content README.md package.json package-lock.json; then
  echo 'Commit the build and deployment inputs before releasing.' >&2
  exit 1
fi
if [[ -n $(git ls-files --others --exclude-standard -- src docs scripts supabase test content) ]]; then
  echo 'Commit new deployment inputs before releasing.' >&2
  exit 1
fi
if [[ $(cat supabase/.temp/project-ref) != dvnddujfsmtwhfelfpdc ]]; then
  echo 'Link the SRS Supabase project before deploying.' >&2
  exit 1
fi
# This must succeed before pushing can trigger GitHub Pages.
supabase db push --linked --yes
git push origin main

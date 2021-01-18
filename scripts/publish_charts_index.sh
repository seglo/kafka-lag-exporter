#!/usr/bin/env bash

set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null && pwd )"

cd $DIR/..

CHARTS_DIR=".helm-release-packages"
GITHUB_PAGES_BRANCH="gh-pages"

function pages_branch_missing() {
    local existed_in_remote=$(git ls-remote --heads origin $GITHUB_PAGES_BRANCH)

    [ -z "${existed_in_remote}" ]
}

function create_pages_branch() {
  echo "Creating pages branch ${GITHUB_PAGES_BRANCH}"
  git checkout --quiet --orphan $GITHUB_PAGES_BRANCH
  git rm --quiet -rf .
  git commit --quiet --allow-empty -m "Pages initial commit"
  git push --quiet origin HEAD:refs/heads/$GITHUB_PAGES_BRANCH
  git checkout --detach --quiet
  git branch --quiet -d $GITHUB_PAGES_BRANCH
}

function add_pages_worktree() {
  local worktree_dir=${1}

  if pages_branch_missing; then
    git worktree add --quiet --detach ${worktree_dir}
    pushd ${worktree_dir} > /dev/null
    create_pages_branch
    popd > /dev/null
  else
    git worktree add --quiet ${worktree_dir} "origin/${GITHUB_PAGES_BRANCH}"
  fi
}

function remove_pages_worktree() {
  local worktree_dir=${1}
  git worktree remove --force ${worktree_dir}
  rm -rf ${worktree_dir}
}

function push_index() {
  if [[ ! -f "${CHARTS_DIR}/index.yaml" ]]; then
    return -1
  fi

  local worktree=$(mktemp -d /tmp/worktree.XXXXXX)
  add_pages_worktree ${worktree}

  cp ${CHARTS_DIR}/index.yaml ${worktree}/
  pushd ${worktree} > /dev/null
  git add index.yaml
  git commit --quiet --message "Update index.yaml"
  git push --quiet origin HEAD:refs/heads/$GITHUB_PAGES_BRANCH
  popd > /dev/null

  remove_pages_worktree ${worktree}
}

push_index



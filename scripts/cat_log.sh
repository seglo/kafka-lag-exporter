#!/bin/sh
# ---------- helper script to separate log files in github actions build
printf "\n\n\n"
ls -lh $1
printf "\n\n"
cat $1

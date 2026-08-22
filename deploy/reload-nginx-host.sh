#!/bin/sh
set -eu

nginx_pid="$(cat /nginx.pid)"
kill -HUP "$nginx_pid"

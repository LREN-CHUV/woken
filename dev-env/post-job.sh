#!/bin/sh
cat job.json | sed "s/001/$(date +'%s')/" | curl -XPUT -H "Content-Type: application/json" -H "Accept: application/json" --data @- localhost:8087/job

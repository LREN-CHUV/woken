#!/bin/sh
curl -XPUT -H "Content-Type: application/json" -H "Accept: application/json" --data @job.json localhost:8087/job

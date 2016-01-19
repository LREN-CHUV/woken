#!/bin/bash

id=$1

if [ -z "$id" ]; then
  id=$(date +'%s')
fi

http -v --timeout 180 POST localhost:8087/request \
         variables:='[{"code":"LeftAmygdala"}]' \
         grouping:='[{"code":"COLPROT"}]' \
         covariables:='[{"code":"AGE"}]' \
         filters:='[]' \
         request:='{"plot":"boxplot"}'

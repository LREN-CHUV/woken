#!/bin/bash

id=$1

if [ -z "$id" ]; then
  id=$(date +'%s')
fi

http -v --timeout 180 POST localhost:8087/request \
         variables:='[{"code":"tissue1_volume"}]' \
         grouping:='[]' \
         covariables:='[]' \
         filters:='[]' \
         request:='{"plot":"boxplot"}'

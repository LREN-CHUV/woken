#!/bin/bash

http -v --timeout 180 POST localhost:8087/request \
         variables:='[]' \
         grouping:='[]' \
         covariables:='[{"code":"AGE"},{"code":"LeftAmygdala"}]' \
         filters:='[]' \
         request:='{"plot":"data"}'

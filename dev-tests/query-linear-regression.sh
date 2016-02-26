#!/bin/bash

http -v --timeout 180 POST localhost:8087/request \
         variables:='[{"code":"LeftAmygdala"}]' \
         grouping:='[{"code":"COLPROT"}]' \
         covariables:='[{"code":"AGE"}]' \
         filters:='[]' \
         request:='{"plot":"linearRegression"}'

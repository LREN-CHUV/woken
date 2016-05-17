#!/bin/bash

http -v --timeout 180 POST localhost:8087/mining \
         variables:='[]' \
         grouping:='[]' \
         covariables:='[{"code":"AGE"},{"code":"LeftAmygdala"}]' \
         filters:='[]' \
         algorithm:='{"code":"data", "label": "data", "parameters": []}'

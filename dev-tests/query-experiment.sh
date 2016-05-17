#!/bin/bash

http -v --timeout 180 POST localhost:8087/experiment \
         variables:='[{"code":"LeftAmygdala"}]' \
         grouping:='[{"code":"COLPROT"}]' \
         covariables:='[{"code":"AGE"}]' \
         filters:='[]' \
         algorithms:='[{"code":"linearRegression", "label": "linearRegression", "parameters": []}]' \
         validations:='[{"code":"kfold", "label": "kfold", "parameters": [{"code": "k", "value": "2"}]}]'
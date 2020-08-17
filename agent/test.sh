#!/bin/bash

secret="xxx"
token="yyy"
host="https://laser-qa.hbz-nrw.de"
path="/api/v0/licenseList"
timestamp=""
nonce=""
q="q=ns:identifier&v=DE-206"
auth=`echo -n "GET$path$timestamp$nonce$q" | openssl dgst -sha256 -hmac "$secret" | awk '{print $2}'`
echo sending curl $host$path?$q -H "x-authorization: hmac $token:::$auth,hmac-sha256"
curl -v -v -v $host$path?$q -H "x-authorization: hmac $token:::$auth,hmac-sha256"

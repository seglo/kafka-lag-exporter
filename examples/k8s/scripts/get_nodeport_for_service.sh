#!/usr/bin/env bash
export SERVICE=$1
NODE_PORT=$(kubectl get service ${SERVICE} -o=jsonpath='{.spec.ports[0].nodePort}{"\n"}') &&
NODE_IP=$(minikube ip) &&
echo ${NODE_IP}:${NODE_PORT}

#!/bin/bash

package=`basename "$0"`
DEBUG=false

function print_usage() {
  echo "*****************************************************************************"
  echo "*                   Script To Shutdown An Existing Sotreus                  *"
  echo "*****************************************************************************"
  echo " "
  echo "$package  [arguments]"
  echo " "
  echo "OPTIONS:"
  echo "-h                                  Show Brief Help"
  echo "-i <SOTREUS ID>                        Specify the SOTREUS ID (*required)"
  echo "-d                                  Enable Debug Mode"
  echo " "
}

while getopts 'i:hd' option; do
  case "${option}" in
    h) print_usage
       exit 0 ;;
    i) SOTREUS_NAME="${OPTARG}" ;;
    d) DEBUG=true ;;
    *) print_usage
       exit 1 ;;
  esac
done

[ -z ${SOTREUS_NAME} ] && { printf '{"todo": "Shutdown Sotreus Docker Instances","result": "failure","message": "SOTREUS ID Not Provided"}\n'; exit 2;}

if $DEBUG; then
  echo -e "Shutting Down Sotreus Docker Instances For [$SOTREUS_NAME]..."
  /usr/bin/docker-compose -p $SOTREUS_NAME stop
else
  /usr/bin/docker-compose -p $SOTREUS_NAME stop > /dev/null 2>&1
fi

if [ $? -ne 0 ]; then
  printf '{"todo": "Shutdown Sotreus Docker Instances", "result": "failure", "message": "Error Occured While Shutting Down Containers"}\n'
  exit 2
else
  printf '{"todo": "Shutdown Sotreus Docker Instances", "result": "success", "message": "Containers Shutdown Successful"}\n'
  exit 0
fi

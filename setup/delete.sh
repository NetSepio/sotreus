#!/bin/bash

package=`basename "$0"`
DEBUG=false

function print_usage() {
  echo "*****************************************************************************"
  echo "*                  Script To Delete An Existing Sotreus                     *"
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

[ -z ${SOTREUS_NAME} ] && { printf '{"todo": "Delete Sotreus Docker Instances","result": "failure","message": "SOTREUS ID Not Provided"}\n'; exit 2;}

if $DEBUG; then
  echo -e "Deleting Sotreus Docker Instances For [$SOTREUS_NAME]..."
  /usr/bin/docker-compose -f adguard.docker-compose.yml -p $SOTREUS_NAME down
else
  /usr/bin/docker-compose -p $SOTREUS_NAME down > /dev/null 2>&1
fi

if [ $? -ne 0 ]; then
  printf '{"todo": "Delete Sotreus Docker Instances", "result": "failure", "message": "Error Occured While Deleting Containers"}\n'
  exit 2
else
  rm -rf /sotreus/${SOTREUS_NAME}/
  printf '{"todo": "Delete Sotreus Docker Instances", "result": "success", "message": "Containers Deletion Successful"}\n'
  exit 0
fi

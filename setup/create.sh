#!/bin/bash
package=`basename "$0"`
DEBUG=false

function print_usage() {
  echo "*****************************************************************************"
  echo "*                         Script To Create Sotreus                          *"
  echo "*****************************************************************************"
  echo " "
  echo "$package  [arguments]"
  echo " "
  echo "OPTIONS:"
  echo "-h                                  Show Brief Help"
  echo "-i <SOTREUS ID>                     Specify a Unique SOTREUS ID (*required)"
  echo "-e <SOTREUS Endpoint>               Specify the SOTREUS Endpoint/Domain (*required)"
  echo "-p <Endpoint Port>                  Specify the SOTREUS Endpoint Port (*required)"
  echo "-a <SOTREUS API Port>               Specify the SOTREUS API Port (*required)"
  echo "--usepihole <SOTREUS Firewall>      Whether to use pihole firewall (*required)"
  echo "-d                                  Enable Debug Mode"
  echo " "
}

while getopts 'i:e:p:a:f:q:w:hd' option; do
  case "${option}" in
    h) print_usage
       exit 0 ;;
    i) SOTREUS_NAME="${OPTARG}" ;;
    e) WG_ENDPOINT_HOST="${OPTARG}" ;;
    p) WG_ENDPOINT_PORT="${OPTARG}" ;;
    a) WG_HTTP_PORT="${OPTARG}" ;;
    f) FIREWALL_TO_USE="${OPTARG}" ;;
    q) FW_ENDPOINT_PORT="${OPTARG}" ;;
    w) FW_ENDPOINT_HOST="${OPTARG}" ;;
    d) DEBUG=true ;;
    *) print_usage
       exit 1 ;;
  esac
done

[ -z ${SOTREUS_NAME} ] && { printf '{"todo": "Create Sotreus Docker Instances","result": "failure","message": "SOTREUS ID Not Provided"}\n'; exit 2;}
[ -z ${WG_ENDPOINT_HOST} ] && { printf '{"todo": "Create Sotreus Docker Instances","result": "failure","message": "SOTREUS Endpoint/Domain Not Provided"}\n'; exit 2;}
[ -z ${WG_ENDPOINT_PORT} ] && { printf '{"todo": "Create Sotreus Docker Instances","result": "failure","message": "SOTREUS Endpoint Port Not Provided"}\n'; exit 2;}
[ -z ${WG_HTTP_PORT} ] && { printf '{"todo": "Create Sotreus Docker Instances","result": "failure","message": "SOTREUS API Port Not Provided"}\n'; exit 2;}
[ -z ${FIREWALL_TO_USE} ] && { printf '{"todo": "Create Sotreus Docker Instances","result": "failure","message": "Firewall name Not Provided"}\n'; exit 2;}
[ -z ${FW_ENDPOINT_PORT} ] && { printf '{"todo": "Create Sotreus Docker Instances","result": "failure","message": "Firewall endpoint Not Provided"}\n'; exit 2;}
[ -z ${FW_ENDPOINT_HOST} ] && { printf '{"todo": "Create Sotreus Docker Instances","result": "failure","message": "Firewall host Not Provided"}\n'; exit 2;}

FIREWALL_PASSWORD=`head /dev/urandom | tr -dc A-Za-z0-9 | head -c 13 ; echo ''`
if [ "$FIREWALL_TO_USE" == "pihole" ]; then
  DOCKER_COMPOSE_FILE="pihole.docker-compose.yml"
else 
  DOCKER_COMPOSE_FILE="adguard.docker-compose.yml"
fi
echo $DOCKER_COMPOSE_FILE
if $DEBUG; then
  echo -e "Creating Docker Instances For Sotreus For [$SOTREUS_NAME]..."
  SOTREUS_NAME=$SOTREUS_NAME WG_ENDPOINT_HOST=$WG_ENDPOINT_HOST WG_ENDPOINT_PORT=$WG_ENDPOINT_PORT WG_HTTP_PORT=$WG_HTTP_PORT FIREWALL_PASSWORD=$FIREWALL_PASSWORD DOMAIN=$WG_ENDPOINT_HOST FW_ENDPOINT_PORT=$FW_ENDPOINT_PORT FW_ENDPOINT_HOST=$FW_ENDPOINT_HOST /usr/bin/docker-compose -f $DOCKER_COMPOSE_FILE -p $SOTREUS_NAME up -d
else
  SOTREUS_NAME=$SOTREUS_NAME WG_ENDPOINT_HOST=$WG_ENDPOINT_HOST WG_ENDPOINT_PORT=$WG_ENDPOINT_PORT WG_HTTP_PORT=$WG_HTTP_PORT FIREWALL_PASSWORD=$FIREWALL_PASSWORD DOMAIN=$WG_ENDPOINT_HOST FW_ENDPOINT_PORT=$FW_ENDPOINT_PORT FW_ENDPOINT_HOST=$FW_ENDPOINT_HOST /usr/bin/docker-compose -f $DOCKER_COMPOSE_FILE -p $SOTREUS_NAME up -d > /dev/null 2>&1
fi
mkdir -p /sotreus/$SOTREUS_NAME/etc-wireguard/clients

if [ "$FIREWALL_TO_USE" == "adguard" ]; then
  jsonBody=$(printf '{"web":{"ip":"0.0.0.0","port":80,"status":"","can_autofix":false},"dns":{"ip":"0.0.0.0","port":53,"status":"","can_autofix":false},"username":"admin","password":"%s"}' "$FIREWALL_PASSWORD");

  curl --header "Content-Type: application/json" \
      --request POST \
      --data $jsonBody \
      http://localhost:8090/control/install/configure

fi

function get_firewall_src() {
  TMP_DIR=/tmp/`head /dev/urandom | tr -dc A-Za-z0-9 | head -c 13 ; echo ''`
  mkdir $TMP_DIR && cd $TMP_DIR
  git clone https://github.com/pi-hole/AdminLTE.git
  echo $TMP_DIR
}

if [ "$FIREWALL_TO_USE" == "pihole" ]; then
  SOTREUS_BASE_DIR=/sotreus
  SRC_DIR=$( get_firewall_src )
  cp -R $SRC_DIR/AdminLTE/* $SOTREUS_BASE_DIR/$SOTREUS_NAME/pihole-dashboard/
  rm -rf $SRC_DIR
fi
if [ $? -ne 0 ]; then
  printf '{"todo": "Create Sotreus Docker Instances", "result": "failure", "message": "Error Occured While Creating Containers"}\n'
  exit 2
else
  printf '{"todo": "Create Sotreus Docker Instances", "result": "success", "message": {"vpn_id": "%s", "vpn_endpoint": "%s", "vpn_api_port": %s, "vpn_external_port": %s,"dashboard_password":"%s"}}\n' "$SOTREUS_NAME" "$WG_ENDPOINT_HOST" "$WG_HTTP_PORT" "$WG_ENDPOINT_PORT" "$FIREWALL_PASSWORD"
  exit 0
fi

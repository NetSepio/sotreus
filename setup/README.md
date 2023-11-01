# Sotreus Setup
Scripts for setting up Sotreus Service.

1. Create Containers
```
./create.sh -i jp99 -e "jp99.sotreus.com" -p 51820 -a 9080 -f pihole

{"todo": "Create Sotreus Docker Instances", "result": "success", "message": {"vpn_id": jp99, "vpn_endpoint": jp99.lazarus.network, "vpn_api_port": 9080, "vpn_external_port": 51820}}
```
2. Shutdown Containers
```
./shutdown.sh -i jp99

{"todo": "Shutdown Sotreus Docker Instances", "result": "success", "message": "Containers Shutdown Successful"}
 ```
3. Start Containers
```
./start.sh -i jp99

{"todo": "Start Sotreus Docker Instances", "result": "success", "message": "Containers Started Successfully"}
```
4. Delete Containers
```
./delete.sh -i jp99

{"todo": "Delete Sotreus Docker Instances", "result": "success", "message": "Containers Deletion Successful"}
```

### Additional Commands
0. ```docker-compose -p jp99 ps```
1. ```docker-compose exec -it <containername> bash```
2. ```sudo netstat -pna | grep 51820```
3. ```sudo lsof -i -P -n | grep 51820```
4. ```docker rm -f $(docker ps -aq)```

### TODO
Declare Named Docker Volumes for easy clean up
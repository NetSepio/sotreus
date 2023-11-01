# Sotreus

Sotreus is an open source VPN solution from The Lazarus Network, that helps to deploy your own dedicated VPN
solution with firewall in minutes.The vision of Sotreus is to deliver Cyber security to everyone.

## Features

- Easy Client and Server management.
- Supports REST and gRPC.
- Email VPN configuration to clients easily.
- Easily deployable with AdGuard or PiHole

## Firewalls

Currently we support PiHole and AdGuard firewall

- This firewalls are created in docker compose as a service
- This firewalls are based on DNS and are used interally with the VPN
- The password for firewall is created in `create.sh` and can be used while accessing dashboard with the username `admin`

## Get Started

To deploy Sotreus, you need to follow the documentation given below,

- First you will need to setup Wireguard and Watcher, for that use [setup docs](https://github.com/NetSepio/sotreus/blob/main/docs/setup.md).
- After setup , you will have choices for deploying Sotreus. Refer [Deploy docs](https://github.com/NetSepio/sotreus/blob/main/docs/deploy.md)

## API Docs

There was two types of docs available

- you can refer docs from github [here](https://github.com/NetSepio/sotreus/blob/main/docs/docs.md)
- Download Postman collection for Sotreus from [here](https://github.com/NetSepio/sotreus/blob/main/docs/Sotreus.postman_collection.json)
- There is a web based doc available on Sotreus route /docs. You can refer it after deployment

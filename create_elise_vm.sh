#!/bin/bash
set -e
echo "=== ELISE — Creation VM Oracle Cloud ==="
CID=$(oci iam availability-domain list --query "data[0].\"compartment-id\"" --raw-output)
echo "Compartment: $CID"
IMG=$(oci compute image list --compartment-id $CID --operating-system "Canonical Ubuntu" --operating-system-version "22.04" --sort-by TIMECREATED --sort-order DESC --query "data[0].id" --raw-output)
echo "Image: $IMG"
VCN=$(oci network vcn list --compartment-id $CID --query "data[0].id" --raw-output 2>/dev/null)
if [ -z "$VCN" ] || [ "$VCN" = "null" ]; then
  VCN=$(oci network vcn create --compartment-id $CID --cidr-block "10.0.0.0/16" --display-name "elise-vcn" --query "data.id" --raw-output)
  IGW=$(oci network internet-gateway create --compartment-id $CID --vcn-id $VCN --is-enabled true --display-name "elise-igw" --query "data.id" --raw-output)
  RT=$(oci network route-table list --compartment-id $CID --vcn-id $VCN --query "data[0].id" --raw-output)
  oci network route-table update --rt-id $RT --force --route-rules "[{\"cidrBlock\":\"0.0.0.0/0\",\"networkEntityId\":\"$IGW\"}]" >/dev/null
  SL=$(oci network security-list list --compartment-id $CID --vcn-id $VCN --query "data[0].id" --raw-output)
  oci network security-list update --security-list-id $SL --force --ingress-security-rules '[{"protocol":"6","source":"0.0.0.0/0","tcpOptions":{"destinationPortRange":{"min":22,"max":22}}},{"protocol":"6","source":"0.0.0.0/0","tcpOptions":{"destinationPortRange":{"min":80,"max":80}}},{"protocol":"6","source":"0.0.0.0/0","tcpOptions":{"destinationPortRange":{"min":443,"max":443}}}]' --egress-security-rules '[{"protocol":"all","destination":"0.0.0.0/0"}]' >/dev/null
  SUBNET=$(oci network subnet create --compartment-id $CID --vcn-id $VCN --cidr-block "10.0.0.0/24" --display-name "elise-subnet" --query "data.id" --raw-output)
else
  SUBNET=$(oci network subnet list --compartment-id $CID --vcn-id $VCN --query "data[0].id" --raw-output)
fi
echo "Subnet: $SUBNET"
[ -f ~/.ssh/elise_key ] || ssh-keygen -t rsa -b 4096 -f ~/.ssh/elise_key -N "" -q
PUB=$(cat ~/.ssh/elise_key.pub)
INSTANCE=""
for AD in $(oci iam availability-domain list --compartment-id $CID --query "data[*].name" --raw-output | tr -d '[]"' | tr ',' ' '); do
  echo "Tentative AD: $AD ..."
  INSTANCE=$(oci compute instance launch --compartment-id $CID --availability-domain "$AD" --shape "VM.Standard.A1.Flex" --shape-config '{"ocpus":4,"memoryInGBs":24}' --image-id $IMG --subnet-id $SUBNET --display-name "elise-server" --metadata "{\"ssh_authorized_keys\":\"$PUB\"}" --assign-public-ip true --query "data.id" --raw-output 2>/dev/null) && [ -n "$INSTANCE" ] && [ "$INSTANCE" != "null" ] && break || INSTANCE=""
done
if [ -z "$INSTANCE" ]; then
  echo "UK London plein — souscription US Ashburn..."
  oci iam region-subscription create --region-name "us-ashburn-1" 2>/dev/null && echo "Souscrit ! Attends 3 min puis : export OCI_CLI_REGION=us-ashburn-1 && bash <(curl -s https://raw.githubusercontent.com/marvinemmy/elise-watch/main/create_elise_vm.sh)" || echo "Erreur souscription"
else
  echo "Instance: $INSTANCE — attente IP..."
  sleep 45
  IP=$(oci compute instance list-vnics --instance-id $INSTANCE --query "data[0].\"public-ip\"" --raw-output 2>/dev/null)
  echo ""; echo "=================================="; echo "  VM CREEE — IP : $IP"; echo "  cat ~/.ssh/elise_key  (cle privee)"; echo "=================================="
fi
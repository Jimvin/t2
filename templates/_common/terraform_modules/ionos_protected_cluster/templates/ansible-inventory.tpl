[all:vars]
domain=${domain}
stackable_user=${stackable_user}
stackable_user_home=${stackable_user_home}

[nat]
${nat_public_ip} internal_ip=${nat_internal_ip}

[nat:vars]
ansible_user=${stackable_user}
ansible_ssh_private_key_file=${ssh_key_private_path}
wireguard=${wireguard}
ansible_become=yes

[nodes]
%{ for index, node in nodes ~}
${node.name} ansible_host=${node.primary_ip} stackable_agent=${nodes_has_agent[index]}
%{ endfor ~}

[nodes:vars]
ansible_ssh_common_args= -o ProxyCommand='ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${ssh_key_private_path} -W %h:%p -q ${stackable_user}@${nat_public_ip}'
ansible_ssh_private_key_file=${ssh_key_private_path}
ansible_user=${stackable_user}

[orchestrators]
orchestrator ansible_host=${orchestrator.primary_ip}

[orchestrators:vars]
ansible_ssh_common_args= -o ProxyCommand='ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -i ${ssh_key_private_path} -W %h:%p -q ${stackable_user}@${nat_public_ip}'
ansible_ssh_private_key_file=${ssh_key_private_path}
ansible_user=${stackable_user}

[protected:children]
nodes
orchestrators

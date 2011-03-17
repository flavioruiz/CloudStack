/**
 *  Copyright (C) 2010 Cloud.com, Inc.  All rights reserved.
 * 
 * This software is licensed under the GNU General Public License v3 or later.
 * 
 * It is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 */
package com.cloud.upgrade.dao;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.log4j.Logger;

import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.utils.script.Script;

public class Upgrade217to22 implements DbUpgrade {
    final static Logger s_logger = Logger.getLogger(Upgrade217to22.class);
    boolean _basicZone;

    @Override
    public File[] getPrepareScripts() {
        String file = Script.findScript("","db/schema-21to22.sql");
        if (file == null) {
            throw new CloudRuntimeException("Unable to find the upgrade script, schema-21to22.sql");
        }
        
        return new File[] {new File(file)};
    }
    
    protected void upgradeStoragePools(Connection conn) {
        PreparedStatement pstmt;
        try {
            pstmt = conn.prepareStatement("UPDATE storage_pool SET status='Up'");
            pstmt.executeUpdate();
            pstmt.close();
        } catch(SQLException e) {
            throw new CloudRuntimeException("Can't upgrade storage pool ", e);
        }
    }
    
    protected long insertNetworkOffering(Connection conn, String name, String displayText, 
                                         String trafficType, boolean systemOnly, boolean defaultNetworkOffering,
                                         String availability, boolean dns_service, boolean gateway_service, 
                                         boolean firewall_service, boolean lb_service, 
                                         boolean userdata_service, boolean vpn_service, 
                                         boolean dhcp_service) {
        String insertSql = 
            "INSERT INTO network_offerings (name, display_text, nw_rate, mc_rate, concurrent_connections, traffic_type, tags, system_only, specify_vlan, service_offering_id, created, removed, `default`, availability, dns_service, gateway_service, firewall_service, lb_service, userdata_service, vpn_service, dhcp_service) " +
                                   "VALUES (?,    ?,            NULL,    NULL,    NULL,                   ?,            NULL, ?,           0,            NULL,                now(),   NULL,    ?,       ?,            ?,           ?,               ?,                ?,          ?,                ?,           ?)";
        try {
            PreparedStatement pstmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            pstmt.setString(i++, name);
            pstmt.setString(i++, displayText);
            pstmt.setString(i++, trafficType);
            pstmt.setBoolean(i++, systemOnly);
            pstmt.setBoolean(i++, defaultNetworkOffering);
            pstmt.setString(i++, availability);
            pstmt.setBoolean(i++, dns_service);
            pstmt.setBoolean(i++, gateway_service);
            pstmt.setBoolean(i++, firewall_service);
            pstmt.setBoolean(i++, lb_service);
            pstmt.setBoolean(i++, userdata_service);
            pstmt.setBoolean(i++, vpn_service);
            pstmt.setBoolean(i++, dhcp_service);
            pstmt.executeUpdate();
            ResultSet rs = pstmt.getGeneratedKeys();
            rs.next();
            long id = rs.getLong(1);
            rs.close();
            pstmt.close();
            return id;
        } catch (SQLException e) {
            throw new CloudRuntimeException("Unable to insert network offering ", e);
        }        
    }
    
    protected void upgradeInstanceGroups(Connection conn){
        try {
            
            //Create instance groups - duplicated names are allowed across accounts
            PreparedStatement pstmt = conn.prepareStatement("SELECT DISTINCT v.group, v.account_id from vm_instance v where v.group is not null");
            ResultSet rs = pstmt.executeQuery();
            ArrayList<Object[]> groups = new ArrayList<Object[]>();
            while (rs.next()) {
                Object[] group = new Object[10];
                group[0] = rs.getString(1); // group name
                group[1] = rs.getLong(2);  // accountId
                groups.add(group);
            }
            rs.close();
            pstmt.close();
            
            for (Object[] group : groups) {
                String groupName = (String)group[0];
                Long accountId = (Long)group[1];
                createInstanceGroups(conn, groupName, accountId);
            } 
            
            //update instance_group_vm_map
            pstmt = conn.prepareStatement("SELECT g.id, v.id from vm_instance v, instance_group g where g.name=v.group and g.account_id=v.account_id and v.group is not null");
            rs = pstmt.executeQuery();
            ArrayList<Object[]> groupVmMaps = new ArrayList<Object[]>();
            while (rs.next()) {
                Object[] groupMaps = new Object[10];
                groupMaps[0] = rs.getLong(1); // vmId
                groupMaps[1] = rs.getLong(2);  // groupId
                groupVmMaps.add(groupMaps);
            }
            rs.close();
            pstmt.close();
            
            for (Object[] groupMap : groupVmMaps) {
                Long groupId = (Long)groupMap[0];
                Long instanceId = (Long)groupMap[1];
                createInstanceGroupVmMaps(conn, groupId, instanceId);
            }  
        } catch (SQLException e) {
            throw new CloudRuntimeException("Can't update instance groups ", e);
        }
        
    }
    
    protected void createInstanceGroups(Connection conn, String groupName, long accountId) throws SQLException {
        PreparedStatement pstmt = conn.prepareStatement("INSERT INTO instance_group (account_id, name, created) values (?, ?, now()) ");
        pstmt.setLong(1, accountId);
        pstmt.setString(2, groupName);
        pstmt.executeUpdate();
        pstmt.close();
    }
    
    protected void createInstanceGroupVmMaps(Connection conn, long groupId, long instanceId) throws SQLException {
        PreparedStatement pstmt = conn.prepareStatement("INSERT INTO instance_group_vm_map (group_id, instance_id) values (?, ?) ");
        pstmt.setLong(1, groupId);
        pstmt.setLong(2, instanceId);
        pstmt.executeUpdate();
        pstmt.close();
    }
    
    protected void insertNic(Connection conn, long networkId, long instanceId, boolean running, String macAddress, String ipAddress, String netmask, String strategy, String gateway, String vnet, String guru, boolean defNic, int deviceId, String mode, String reservationId) throws SQLException {
        PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO nics (instance_id, network_id, mac_address, ip4_address, netmask, strategy, ip_type, broadcast_uri, mode, reserver_name, reservation_id, device_id, update_time, isolation_uri, ip6_address, default_nic, created, removed, state, gateway) " +
                          "VALUES (?,           ?,          ?,           ?,           ?,       ?,        'Ip4',   ?,             ?,    ?,             ?,              ?,         now(),       ?,          NULL,         ?,          now(),   NULL,    ?,     ?)");
        int i = 1;
        String isolationUri = null;
        
        String broadcast = null;
        if (vnet != null) {
            broadcast = "vlan://" + vnet;
            if (vnet.equalsIgnoreCase("untagged")) {
                isolationUri = "ec2://" + vnet;
            } else {
                isolationUri = broadcast;
            }
        }
        pstmt.setLong(i++, instanceId);
        pstmt.setLong(i++, networkId);
        pstmt.setString(i++, macAddress);
        pstmt.setString(i++, ipAddress);
        pstmt.setString(i++, netmask);
        pstmt.setString(i++, strategy);
        pstmt.setString(i++, broadcast);
        pstmt.setString(i++, mode);
        pstmt.setString(i++, guru);
        pstmt.setString(i++, reservationId);
        pstmt.setInt(i++, deviceId);
        pstmt.setString(i++, isolationUri);
        pstmt.setBoolean(i++, defNic);
        pstmt.setString(i++, running ? "Reserved" : "Allocated");
        pstmt.setString(i++, gateway);
        pstmt.executeUpdate();
        pstmt.close();
    }
    
    protected void upgradeDomR(Connection conn, long domrId, Long publicNetworkId, long guestNetworkId, long controlNetworkId, String zoneType) throws SQLException {
        s_logger.debug("Upgrading domR" + domrId);
        PreparedStatement pstmt = conn.prepareStatement("SELECT vm_instance.id, vm_instance.state, vm_instance.private_mac_address, vm_instance.private_ip_address, vm_instance.private_netmask, domain_router.public_mac_address, domain_router.public_ip_address, domain_router.public_netmask, domain_router.guest_mac_address, domain_router.guest_ip_address, domain_router.guest_netmask, domain_router.vnet, domain_router.gateway, vm_instance.type FROM vm_instance INNER JOIN domain_router ON vm_instance.id=domain_router.id WHERE vm_instance.removed is NULL AND vm_instance.id=?");
        pstmt.setLong(1, domrId);
        ResultSet rs = pstmt.executeQuery();
        
        if (!rs.next()) {
            throw new CloudRuntimeException("Unable to find router " + domrId);
        }
        
        long id = rs.getLong(1);
        String state = rs.getString(2);
        boolean running = state.equals("Running") | state.equals("Starting") | state.equals("Stopping");
        String privateMac = rs.getString(3);
        String privateIp = rs.getString(4);
        String privateNetmask = rs.getString(5);
        String publicMac = rs.getString(6);
        String publicIp = rs.getString(7);
        String publicNetmask = rs.getString(8);
        String guestMac = rs.getString(9);
        String guestIp = rs.getString(10);
        String guestNetmask = rs.getString(11);
        String vnet = rs.getString(12);
        String gateway = rs.getString(13);
        String type = rs.getString(14);
        rs.close();
        pstmt.close();
        
        if (zoneType.equalsIgnoreCase("Basic")) {
            insertNic(conn, controlNetworkId, domrId, running, privateMac, privateIp, privateNetmask, "Start", "169.254.0.1", null, "ControlNetworkGuru", false, 1, "Static", privateIp != null ? (domrId + privateIp) : null);
            insertNic(conn, guestNetworkId, domrId, running, guestMac, guestIp, guestNetmask, "Start", gateway, vnet, "DirectPodBasedNetworkGuru", false, 0, "Static", null);
        } else {
            insertNic(conn, publicNetworkId, domrId, running, publicMac, publicIp, publicNetmask, "Create", gateway, null, "PublicNetworkGuru", true, 2, "Static", null);
            insertNic(conn, controlNetworkId, domrId, running, privateMac, privateIp, privateNetmask, "Start", "169.254.0.1", null, "ControlNetworkGuru", false, 1, "Static", privateIp != null ? (domrId + privateIp) : null);
            insertNic(conn, guestNetworkId, domrId, running, guestMac, guestIp, guestNetmask, "Start", null, vnet, "GuestNetworkGuru", false, 0, "Static", null);
        }
        
        
    }
    
    protected void upgradeSsvm(Connection conn, long dataCenterId, long publicNetworkId, long managementNetworkId, long controlNetworkId, String zoneType) throws SQLException {
        s_logger.debug("Upgrading ssvm in " + dataCenterId);
        PreparedStatement pstmt = conn.prepareStatement("SELECT vm_instance.id, vm_instance.state, vm_instance.private_mac_address, vm_instance.private_ip_address, vm_instance.private_netmask, secondary_storage_vm.public_mac_address, secondary_storage_vm.public_ip_address, secondary_storage_vm.public_netmask, secondary_storage_vm.guest_mac_address, secondary_storage_vm.guest_ip_address, secondary_storage_vm.guest_netmask, secondary_storage_vm.gateway, vm_instance.type FROM vm_instance INNER JOIN secondary_storage_vm ON vm_instance.id=secondary_storage_vm.id WHERE vm_instance.removed is NULL AND vm_instance.data_center_id=? AND vm_instance.type='SecondaryStorageVm'");
        pstmt.setLong(1, dataCenterId);
        ResultSet rs = pstmt.executeQuery();
        
        if (!rs.next()) {
            s_logger.debug("Unable to find ssvm in data center " + dataCenterId);
            return;
        }
        
        long ssvmId = rs.getLong(1);
        String state = rs.getString(2);
        boolean running = state.equals("Running") | state.equals("Starting") | state.equals("Stopping");
        String privateMac = rs.getString(3);
        String privateIp = rs.getString(4);
        String privateNetmask = rs.getString(5);
        String publicMac = rs.getString(6);
        String publicIp = rs.getString(7);
        String publicNetmask = rs.getString(8);
        String guestMac = rs.getString(9);
        String guestIp = rs.getString(10);
        String guestNetmask = rs.getString(11);
        String gateway = rs.getString(12);
        String type = rs.getString(13);
        rs.close();
        pstmt.close();
        
        pstmt = conn.prepareStatement("SELECT host_pod_ref.gateway from host_pod_ref INNER JOIN vm_instance ON vm_instance.pod_id=host_pod_ref.id WHERE vm_instance.removed is NULL AND vm_instance.data_center_id=? AND vm_instance.type='SecondaryStorageVm'");
        pstmt.setLong(1, dataCenterId);
        rs = pstmt.executeQuery();
        
        if (!rs.next()) {
            s_logger.debug("Unable to find ssvm in data center " + dataCenterId);
            return;
        }
        
        String podGateway = rs.getString(1);
        rs.close();
        pstmt.close();
        
        s_logger.debug("Gateway is " + podGateway);
        
        if (zoneType.equalsIgnoreCase("Basic")) {
            insertNic(conn, publicNetworkId, ssvmId, running, publicMac, publicIp, publicNetmask, "Start", gateway, null, "DirectPodBasedNetworkGuru", true, 2, "Static", null);

        } else {
            insertNic(conn, publicNetworkId, ssvmId, running, publicMac, publicIp, publicNetmask, "Create", gateway, null, "PublicNetworkGuru", true, 2, "Static", null);
        }
        
        insertNic(conn, controlNetworkId, ssvmId, running, guestMac, guestIp, guestNetmask, "Start", "169.254.0.1", null, "ControlNetworkGuru", false, 0, "Static", guestIp != null ? (ssvmId + guestIp) : null);
        insertNic(conn, managementNetworkId, ssvmId, running, privateMac, privateIp, privateNetmask, "Start", podGateway, null, "PodBasedNetworkGuru", false, 1, "Static", null);
    }
    
    protected void upgradeConsoleProxy(Connection conn, long cpId, long publicNetworkId, long managementNetworkId, long controlNetworkId, String zoneType) throws SQLException {
        s_logger.debug("Upgrading cp" + cpId);
        PreparedStatement pstmt = conn.prepareStatement("SELECT vm_instance.id, vm_instance.state, vm_instance.private_mac_address, vm_instance.private_ip_address, vm_instance.private_netmask, console_proxy.public_mac_address, console_proxy.public_ip_address, console_proxy.public_netmask, console_proxy.guest_mac_address, console_proxy.guest_ip_address, console_proxy.guest_netmask, console_proxy.gateway, vm_instance.type FROM vm_instance INNER JOIN console_proxy ON vm_instance.id=console_proxy.id WHERE vm_instance.removed is NULL AND vm_instance.id=?");
        pstmt.setLong(1, cpId);
        ResultSet rs = pstmt.executeQuery();
        
        if (!rs.next()) {
            throw new CloudRuntimeException("Unable to find cp " + cpId);
        }
        
        long id = rs.getLong(1);
        String state = rs.getString(2);
        boolean running = state.equals("Running") | state.equals("Starting") | state.equals("Stopping");
        String privateMac = rs.getString(3);
        String privateIp = rs.getString(4);
        String privateNetmask = rs.getString(5);
        String publicMac = rs.getString(6);
        String publicIp = rs.getString(7);
        String publicNetmask = rs.getString(8);
        String guestMac = rs.getString(9);
        String guestIp = rs.getString(10);
        String guestNetmask = rs.getString(11);
        String gateway = rs.getString(12);
        String type = rs.getString(13);
        rs.close();
        pstmt.close();
        
        pstmt = conn.prepareStatement("SELECT host_pod_ref.gateway from host_pod_ref INNER JOIN vm_instance ON vm_instance.pod_id=host_pod_ref.id WHERE vm_instance.id=?");
        pstmt.setLong(1, cpId);
        rs = pstmt.executeQuery();
        
        if (!rs.next()) {
            throw new CloudRuntimeException("Unable to find cp " + cpId);
        }
        
        String podGateway = rs.getString(1);
        rs.close();
        pstmt.close();
        
        
        if (zoneType.equalsIgnoreCase("Basic")) {
            insertNic(conn, publicNetworkId, cpId, running, publicMac, publicIp, publicNetmask, "Start", gateway, null, "DirectPodBasedNetworkGuru", true, 2, "Static", null);
        } else {
            insertNic(conn, publicNetworkId, cpId, running, publicMac, publicIp, publicNetmask, "Create", gateway, null, "PublicNetworkGuru", true, 2, "Static", null);
        }
        
        insertNic(conn, controlNetworkId, cpId, running, guestMac, guestIp, guestNetmask, "Start", "169.254.0.1", null, "ControlNetworkGuru", false, 0, "Static", guestIp != null ? (cpId + guestIp) : null);
        insertNic(conn, managementNetworkId, cpId, running, privateMac, privateIp, privateNetmask, "Start", podGateway, null, "PodBasedNetworkGuru", false, 1, "Static", null);
    }
    
    protected void upgradeVirtualUserVms(Connection conn, long domainRouterId, long networkId, String gateway, String vnet) throws SQLException {
        PreparedStatement pstmt = conn.prepareStatement("SELECT vm_instance.id, vm_instance.private_mac_address, vm_instance.private_ip_address, vm_instance.private_netmask, vm_instance.state, vm_instance.type FROM vm_instance INNER JOIN user_vm ON vm_instance.id=user_vm.id WHERE user_vm.domain_router_id=? and vm_instance.removed IS NULL");
        pstmt.setLong(1, domainRouterId);
        ResultSet rs = pstmt.executeQuery();
        List<Object[]> vms = new ArrayList<Object[]>();
        while (rs.next()) {
            Object[] vm = new Object[10];
            vm[0] = rs.getLong(1); // vm id
            vm[1] = rs.getString(2); // mac address
            vm[2] = rs.getString(3); // ip address
            vm[3] = rs.getString(4); // netmask
            vm[4] = rs.getString(5);  // vm state
            vms.add(vm);
        }
        rs.close();
        pstmt.close();
        
        s_logger.debug("Upgrading " + vms.size() + " vms for router " + domainRouterId);
        
        for (Object[] vm : vms) {
            String state = (String)vm[4];
            
            boolean running = false;
            if (state.equals("Running") || state.equals("Starting") || state.equals("Stopping")) {
                running = true;
            }
            
            insertNic(conn, networkId, (Long)vm[0], running, (String)vm[1], (String)vm[2], (String)vm[3], "Start", gateway, vnet, "GuestNetworkGuru", true, 0, "Dhcp", null);
        }
    }
    
    
    protected void upgradeBasicUserVms(Connection conn, long domainRouterId, long networkId, String gateway, String vnet) throws SQLException {
        PreparedStatement pstmt = conn.prepareStatement("SELECT vm_instance.id, vm_instance.private_mac_address, vm_instance.private_ip_address, vm_instance.private_netmask, vm_instance.state, vm_instance.type FROM vm_instance INNER JOIN user_vm ON vm_instance.id=user_vm.id WHERE user_vm.domain_router_id=? and vm_instance.removed IS NULL");
        pstmt.setLong(1, domainRouterId);
        ResultSet rs = pstmt.executeQuery();
        List<Object[]> vms = new ArrayList<Object[]>();
        while (rs.next()) {
            Object[] vm = new Object[10];
            vm[0] = rs.getLong(1); // vm id
            vm[1] = rs.getString(2); // mac address
            vm[2] = rs.getString(3); // ip address
            vm[3] = rs.getString(4); // netmask
            vm[4] = rs.getString(5);  // vm state
            vm[5] = rs.getString(6);  // vm type
            vms.add(vm);
        }
        rs.close();
        pstmt.close();
        
        s_logger.debug("Upgrading " + vms.size() + " vms for router " + domainRouterId);
        
        for (Object[] vm : vms) {
            String state = (String)vm[4];
            
            boolean running = false;
            if (state.equals("Running") || state.equals("Starting") || state.equals("Stopping")) {
                running = true;
            }
            insertNic(conn, networkId, (Long)vm[0], running, (String)vm[1], (String)vm[2], (String)vm[3], "Start", gateway, vnet, "DirectPodBasedNetworkGuru", true, 0, "Dhcp", null);
            
        }
    }

    
    protected long insertNetwork(Connection conn, String name, String displayText, String trafficType, String broadcastDomainType, String broadcastUri,
                                 String gateway, String cidr, String mode, long networkOfferingId, long dataCenterId, String guruName,
                                 String state, long domainId, long accountId, String dns1, String dns2, String guestType, boolean shared,
                                 String networkDomain, boolean isDefault, String reservationId) {
        String getNextNetworkSequenceSql = "SELECT value from sequence where name='networks_seq'";
        String advanceNetworkSequenceSql = "UPDATE sequence set value=value+1 where name='networks_seq'";
        String insertNetworkSql = "INSERT INTO networks(id, name, display_text, traffic_type, broadcast_domain_type, gateway, cidr, mode, network_offering_id, data_center_id, guru_name, state, domain_id, account_id, dns1, dns2, guest_type, shared, is_default, created, network_domain, related, reservation_id, broadcast_uri) " + 
                                                "VALUES(?,  ?,    ?,            ?,            ?,                     ?,       ?,    ?,    ?,                   ?,              ?,         ?,     ?,         ?,          ?,    ?,    ?,          ?,      ?,          now(),   ?,              ?,       ?,              ?)"; 
        try {
            PreparedStatement pstmt = conn.prepareStatement(getNextNetworkSequenceSql);
            ResultSet rs = pstmt.executeQuery();
            rs.next();
            long seq = rs.getLong(1);
            rs.close();
            pstmt.close();
            
            pstmt = conn.prepareStatement(advanceNetworkSequenceSql);
            pstmt.executeUpdate();
            pstmt.close();
            
            pstmt = conn.prepareStatement(insertNetworkSql);
            int i = 1;
            pstmt.setLong(i++, seq);
            pstmt.setString(i++, name);
            pstmt.setString(i++, displayText);
            pstmt.setString(i++, trafficType);
            pstmt.setString(i++, broadcastDomainType);
            pstmt.setString(i++, gateway);
            pstmt.setString(i++, cidr);
            pstmt.setString(i++, mode);
            pstmt.setLong(i++, networkOfferingId);
            pstmt.setLong(i++, dataCenterId);
            pstmt.setString(i++, guruName);
            pstmt.setString(i++, state);
            pstmt.setLong(i++, domainId);
            pstmt.setLong(i++, accountId);
            pstmt.setString(i++, dns1);
            pstmt.setString(i++, dns2);
            pstmt.setString(i++, guestType);
            pstmt.setBoolean(i++, shared);
            pstmt.setBoolean(i++, isDefault);
            pstmt.setString(i++, networkDomain);
            pstmt.setLong(i++, seq);
            pstmt.setString(i++, reservationId);
            pstmt.setString(i++, broadcastUri);
            pstmt.executeUpdate();
            return seq;
        } catch (SQLException e) {
            throw new CloudRuntimeException("Unable to create network", e);
        }
    }
    
    protected void upgradeManagementIpAddress(Connection conn, long dcId) throws SQLException {
        PreparedStatement pstmt = conn.prepareStatement("SELECT op_dc_ip_address_alloc.id FROM op_dc_ip_address_alloc WHERE data_center_id=?");
        pstmt.setLong(1, dcId);
        ResultSet rs = pstmt.executeQuery();
        ArrayList<Object[]> allocatedIps = new ArrayList<Object[]>();
        while (rs.next()) {
            Object[] ip = new Object[10];
            ip[0] = rs.getLong(1);  // id
            allocatedIps.add(ip);
        }
        rs.close();
        pstmt.close();
        
        for (Object[] allocatedIp : allocatedIps) {
            pstmt = conn.prepareStatement("SELECT mac_address FROM data_center WHERE id = ?");
            pstmt.setLong(1, dcId);
            rs = pstmt.executeQuery();
            if (!rs.next()) {
                throw new CloudRuntimeException("Unable to get mac address for data center " + dcId);
            }
            long mac = rs.getLong(1);
            rs.close();
            pstmt.close();
            
            pstmt = conn.prepareStatement("UPDATE data_center SET mac_address=mac_address+1 WHERE id = ?");
            pstmt.setLong(1, dcId);
            pstmt.executeUpdate();
            pstmt.close();
            
            pstmt = conn.prepareStatement("UPDATE op_dc_ip_address_alloc SET mac_address=? WHERE id=?");
            pstmt.setLong(1, mac);
            pstmt.setLong(2, (Long)allocatedIp[0]);
            pstmt.executeUpdate();
            pstmt.close();
        }

    }
    
    protected void upgradeDirectUserIpAddress(Connection conn, long dcId, long networkId, String vlanType) throws SQLException {
        s_logger.debug("Upgrading user ip address for data center " + dcId + " network " + networkId + " vlan type " + vlanType);
        PreparedStatement pstmt = conn.prepareStatement("UPDATE user_ip_address INNER JOIN vlan ON user_ip_address.vlan_db_id=vlan.id SET user_ip_address.source_network_id=vlan.network_id WHERE user_ip_address.data_center_id=? AND vlan.vlan_type=?");
        pstmt.setLong(1, dcId);
        pstmt.setString(2, vlanType);
        pstmt.executeUpdate();
        pstmt.close();
        
        pstmt = conn.prepareStatement("SELECT user_ip_address.id, user_ip_address.public_ip_address, user_ip_address.account_id, user_ip_address.allocated FROM user_ip_address INNER JOIN vlan ON vlan.id=user_ip_address.vlan_db_id WHERE user_ip_address.data_center_id = ? AND vlan.vlan_type=?");
        pstmt.setLong(1, dcId);
        pstmt.setString(2, vlanType);
        ResultSet rs = pstmt.executeQuery();
        ArrayList<Object[]> allocatedIps = new ArrayList<Object[]>();
        while (rs.next()) {
            Object[] ip = new Object[10];
            ip[0] = rs.getLong(1);  // id
            ip[1] = rs.getString(2); // ip address
            ip[2] = rs.getLong(3); // account id
            ip[3] = rs.getDate(4); // allocated
            allocatedIps.add(ip);
        }
        rs.close();
        pstmt.close();
        
        s_logger.debug("Marking " + allocatedIps.size() + " ip addresses to belong to network " + networkId);
        
        for (Object[] allocatedIp : allocatedIps) {
            s_logger.debug("Updating mac addresses for data center id=" + dcId);
            pstmt = conn.prepareStatement("SELECT mac_address FROM data_center WHERE id = ?");
            pstmt.setLong(1, dcId);
            rs = pstmt.executeQuery();
            if (!rs.next()) {
                throw new CloudRuntimeException("Unable to get mac address for data center " + dcId);
            }
            long mac = rs.getLong(1);
            rs.close();
            pstmt.close();
            
            pstmt = conn.prepareStatement("UPDATE data_center SET mac_address=mac_address+1 WHERE id = ?");
            pstmt.setLong(1, dcId);
            pstmt.executeUpdate();
            pstmt.close();
            
            pstmt = conn.prepareStatement("UPDATE user_ip_address SET mac_address=? WHERE id=?");
            pstmt.setLong(1, mac);
            pstmt.setLong(2, (Long)allocatedIp[0]);
            pstmt.executeUpdate();
            pstmt.close();
        }
    }
    
    protected void upgradePublicUserIpAddress(Connection conn, long dcId, long networkId, String vlanType) throws SQLException {
        s_logger.debug("Upgrading user ip address for data center " + dcId + " network " + networkId + " vlan type " + vlanType);
        PreparedStatement pstmt = conn.prepareStatement("UPDATE user_ip_address INNER JOIN vlan ON user_ip_address.vlan_db_id=vlan.id SET source_network_id=? WHERE user_ip_address.data_center_id=? AND vlan.vlan_type=?");
        pstmt.setLong(1, networkId);
        pstmt.setLong(2, dcId);
        pstmt.setString(3, vlanType);
        pstmt.executeUpdate();
        pstmt.close();
        
        pstmt = conn.prepareStatement("UPDATE vlan SET network_id = ? WHERE data_center_id=? AND vlan_type=?");
        pstmt.setLong(1, networkId);
        pstmt.setLong(2, dcId);
        pstmt.setString(3, vlanType);
        pstmt.executeUpdate();
        pstmt.close();
        
        pstmt = conn.prepareStatement("SELECT user_ip_address.id, user_ip_address.public_ip_address, user_ip_address.account_id, user_ip_address.allocated FROM user_ip_address INNER JOIN vlan ON vlan.id=user_ip_address.vlan_db_id WHERE user_ip_address.data_center_id = ? AND vlan.vlan_type='VirtualNetwork'");
        pstmt.setLong(1, dcId);
        ResultSet rs = pstmt.executeQuery();
        ArrayList<Object[]> allocatedIps = new ArrayList<Object[]>();
        while (rs.next()) {
            Object[] ip = new Object[10];
            ip[0] = rs.getLong(1);  // id
            ip[1] = rs.getString(2); // ip address
            ip[2] = rs.getLong(3); // account id
            ip[3] = rs.getDate(4); // allocated
            allocatedIps.add(ip);
        }
        rs.close();
        pstmt.close();
        
        for (Object[] allocatedIp : allocatedIps) {
            pstmt = conn.prepareStatement("SELECT mac_address FROM data_center WHERE id = ?");
            pstmt.setLong(1, dcId);
            rs = pstmt.executeQuery();
            if (!rs.next()) {
                throw new CloudRuntimeException("Unable to get mac address for data center " + dcId);
            }
            long mac = rs.getLong(1);
            rs.close();
            pstmt.close();
            
            pstmt = conn.prepareStatement("UPDATE data_center SET mac_address=mac_address+1 WHERE id = ?");
            pstmt.setLong(1, dcId);
            pstmt.executeUpdate();
            pstmt.close();
            
            Long associatedNetworkId = null;
            if (allocatedIp[3] != null && allocatedIp[2] != null) {
                pstmt = conn.prepareStatement("SELECT id FROM networks WHERE data_center_id=? AND account_id=?");
                pstmt.setLong(1, dcId);
                pstmt.setLong(2, (Long)allocatedIp[2]);
                rs = pstmt.executeQuery();
                if (!rs.next()) {
                    throw new CloudRuntimeException("Unable to find a network for account " + allocatedIp[2] + " in dc " + dcId);
                }
                associatedNetworkId = rs.getLong(1);
                rs.close();
                pstmt.close();
            }            
            pstmt = conn.prepareStatement("UPDATE user_ip_address SET mac_address=?, network_id=? WHERE id=?");
            pstmt.setLong(1, mac);
            if (associatedNetworkId != null) {
                pstmt.setLong(2, associatedNetworkId);
            } else {
                pstmt.setObject(2, null);
            }
            pstmt.setLong(3, (Long)allocatedIp[0]);
            pstmt.executeUpdate();
            pstmt.close();
        }

    }
            
    protected void upgradeDataCenter(Connection conn) {
        PreparedStatement pstmt;
        try {
            pstmt = conn.prepareStatement("SELECT value FROM configuration WHERE name='direct.attach.untagged.vlan.enabled'");
            ResultSet rs = pstmt.executeQuery();
            _basicZone = !rs.next() || Boolean.parseBoolean(rs.getString(1));
            rs.close();
            pstmt.close();
            pstmt = conn.prepareStatement("UPDATE data_center SET networktype=?, dns_provider=?, gateway_provider=?, firewall_provider=?, dhcp_provider=?, lb_provider=?, vpn_provider=?, userdata_provider=?");
            if (_basicZone) {
                pstmt.setString(1, "Basic");
                pstmt.setString(2, "DhcpServer");
                pstmt.setString(3, null);
                pstmt.setString(4, null);
                pstmt.setString(5, "DhcpServer");
                pstmt.setString(6, null);
                pstmt.setString(7, null);
                pstmt.setString(8, "DhcpServer");
            } else {
                pstmt.setString(1, "Advanced");
                pstmt.setString(2, "VirtualRouter");
                pstmt.setString(3, "VirtualRouter");
                pstmt.setString(4, "VirtualRouter");
                pstmt.setString(5, "VirtualRouter");
                pstmt.setString(6, "VirtualRouter");
                pstmt.setString(7, "VirtualRouter");
                pstmt.setString(8, "VirtualRouter");
            }
            pstmt.executeUpdate();
            pstmt.close();
            
            //For basic zone vnet field should be NULL
            
            if (_basicZone) {
                pstmt = conn.prepareStatement("UPDATE data_center SET vnet=?, guest_network_cidr=?");
                pstmt.setString(1, null);
                pstmt.setString(2, null);
                pstmt.executeUpdate();
                pstmt.close();
            }
            
            pstmt = conn.prepareStatement("SELECT id, guest_network_cidr, domain FROM data_center");
            rs = pstmt.executeQuery();
            ArrayList<Object[]> dcs = new ArrayList<Object[]>();
            while (rs.next()) {
                Object[] dc = new Object[10];
                dc[0] = rs.getLong(1);    // data center id
                dc[1] = rs.getString(2);  // guest network cidr
                dc[2] = rs.getString(3);  // network domain
                dcs.add(dc);
            }
            rs.close();
            pstmt.close();
            
            pstmt = conn.prepareStatement("SELECT id FROM network_offerings WHERE name='System-Management-Network'");
            rs = pstmt.executeQuery();
            if (!rs.next()) {
                throw new CloudRuntimeException("Unable to find the management network offering.");
            }
            long managementNetworkOfferingId = rs.getLong(1);
            rs.close();
            pstmt.close();
            
            pstmt = conn.prepareStatement("SELECT id FROM network_offerings WHERE name='System-Public-Network'");
            rs = pstmt.executeQuery();
            if (!rs.next()) {
                throw new CloudRuntimeException("Unable to find the public network offering.");
            }
            long publicNetworkOfferingId = rs.getLong(1);
            rs.close();
            pstmt.close();
            
            pstmt = conn.prepareStatement("SELECT id FROM network_offerings WHERE name='System-Control-Network'");
            rs = pstmt.executeQuery();
            if (!rs.next()) {
                throw new CloudRuntimeException("Unable to find the control network offering.");
            }
            long controlNetworkOfferingId = rs.getLong(1);
            rs.close();
            pstmt.close();
            
            pstmt = conn.prepareStatement("SELECT id FROM network_offerings WHERE name='System-Storage-Network'");
            rs = pstmt.executeQuery();
            if (!rs.next()) {
                throw new CloudRuntimeException("Unable to find the storage network offering.");
            }
            long storageNetworkOfferingId = rs.getLong(1);
            rs.close();
            pstmt.close();
            
            if (_basicZone) {
                for (Object[] dc : dcs) {
                    Long dcId = (Long)dc[0];
                    long mgmtNetworkId = insertNetwork(conn, "ManagementNetwork" + dcId, "Management Network created for Zone " + dcId, "Management", "Native", null, null, null, "Static", managementNetworkOfferingId, dcId, "PodBasedNetworkGuru", "Setup", 1, 1, null, null, null, true, null, false, null);
                    long storageNetworkId = insertNetwork(conn, "StorageNetwork" + dcId, "Storage Network created for Zone " + dcId, "Storage", "Native", null, null, null, "Static", storageNetworkOfferingId, dcId, "PodBasedNetworkGuru", "Setup", 1, 1, null, null, null, true, null, false, null);
                    long controlNetworkId = insertNetwork(conn, "ControlNetwork" + dcId, "Control Network created for Zone " + dcId, "Control", "Native", null, null, null, "Static", controlNetworkOfferingId, dcId, "ControlNetworkGuru", "Setup", 1, 1, null, null, null, true, null, false, null);
                    upgradeManagementIpAddress(conn, dcId);
                    long basicDefaultDirectNetworkId = insertNetwork(conn, "BasicZoneDirectNetwork" + dcId, "Basic Zone Direct Network created for Zone " + dcId, "Guest", "Native", null, null, null, "Dhcp", 5, dcId, "DirectPodBasedNetworkGuru", "Setup", 1, 1, null, null, "Direct", true, null, true, null);
                
                    
                    pstmt = conn.prepareStatement("SELECT id FROM vlan WHERE vlan_type='DirectAttached' AND data_center_id=?");
                    pstmt.setLong(1, dcId);
                    rs = pstmt.executeQuery();
                    while (rs.next()) {
                        long vlanId = rs.getLong(1);
                        
                        pstmt = conn.prepareStatement("UPDATE vlan SET network_id=? WHERE id=?");
                        pstmt.setLong(1, basicDefaultDirectNetworkId);
                        pstmt.setLong(2, vlanId);
                        pstmt.executeUpdate();
                        pstmt.close();
                        
                        upgradeDirectUserIpAddress(conn, dcId, basicDefaultDirectNetworkId, "DirectAttached");
                    }
                    
                    //update Dhcp servers information in domain_router and vm_instance tables; all domRs belong to the same network
                    pstmt = conn.prepareStatement("SELECT vm_instance.id, vm_instance.domain_id, vm_instance.account_id, domain_router.gateway, domain_router.guest_ip_address, domain_router.domain, domain_router.dns1, domain_router.dns2, domain_router.vnet FROM vm_instance INNER JOIN domain_router ON vm_instance.id=domain_router.id WHERE vm_instance.removed IS NULL AND vm_instance.type='DomainRouter' AND vm_instance.data_center_id=?");
                    pstmt.setLong(1, dcId);
                    rs = pstmt.executeQuery();
                    ArrayList<Object[]> routers = new ArrayList<Object[]>();
                    while (rs.next()) {
                        Object[] router = new Object[40];
                        router[0] = rs.getLong(1); // router id
                        router[1] = rs.getString(4); // router gateway which is gonna be gateway for user vms
                        routers.add(router);
                    }
                    rs.close();
                    pstmt.close();
                    
                    for (Object[] router : routers) {
                        s_logger.debug("Updating domR with network id in basic zone id=" + dcId);
                        pstmt = conn.prepareStatement("UPDATE domain_router SET network_id = ? wHERE id = ? ");
                        pstmt.setLong(1, basicDefaultDirectNetworkId);
                        pstmt.setLong(2, (Long)router[0]);
                        pstmt.executeUpdate();
                        pstmt.close();
                        
                        upgradeBasicUserVms(conn, (Long)router[0], basicDefaultDirectNetworkId, (String)router[1], "untagged");
                        upgradeDomR(conn, (Long)router[0], null, basicDefaultDirectNetworkId, controlNetworkId, "Basic");
                    }
                    
                    upgradeSsvm(conn, dcId, basicDefaultDirectNetworkId, storageNetworkId, controlNetworkId, "Basic");
                    
                    pstmt = conn.prepareStatement("SELECT vm_instance.id FROM vm_instance WHERE removed IS NULL AND type='ConsoleProxy' AND data_center_id=?");
                    pstmt.setLong(1, dcId);
                    rs = pstmt.executeQuery();
                    while (rs.next()) {
                        upgradeConsoleProxy(conn, rs.getLong(1), basicDefaultDirectNetworkId, mgmtNetworkId, controlNetworkId, "Basic");
                    }
                    
                } 
            } else {
                for (Object[] dc : dcs) {
                    Long dcId = (Long)dc[0];
                    long mgmtNetworkId = insertNetwork(conn, "ManagementNetwork" + dcId, "Management Network created for Zone " + dcId, "Management", "Native", null, null, null, "Static", managementNetworkOfferingId, dcId, "PodBasedNetworkGuru", "Setup", 1, 1, null, null, null, true, null, false, null);
                    long storageNetworkId = insertNetwork(conn, "StorageNetwork" + dcId, "Storage Network created for Zone " + dcId, "Storage", "Native", null, null, null, "Static", storageNetworkOfferingId, dcId, "PodBasedNetworkGuru", "Setup", 1, 1, null, null, null, true, null, false, null);
                    long controlNetworkId = insertNetwork(conn, "ControlNetwork" + dcId, "Control Network created for Zone " + dcId, "Control", "Native", null, null, null, "Static", controlNetworkOfferingId, dcId, "ControlNetworkGuru", "Setup", 1, 1, null, null, null, true, null, false, null);
                    upgradeManagementIpAddress(conn, dcId);
                    long publicNetworkId = insertNetwork(conn, "PublicNetwork" + dcId, "Public Network Created for Zone " + dcId, "Public", "Native", null, null, null, "Static", publicNetworkOfferingId, dcId, "PublicNetworkGuru", "Setup", 1,1, null, null, null, true, null, false, null);
                    
                    pstmt = conn.prepareStatement("SELECT vm_instance.id, vm_instance.domain_id, vm_instance.account_id, domain_router.guest_ip_address, domain_router.domain, domain_router.dns1, domain_router.dns2, domain_router.vnet FROM vm_instance INNER JOIN domain_router ON vm_instance.id=domain_router.id WHERE vm_instance.removed IS NULL AND vm_instance.type='DomainRouter' AND vm_instance.data_center_id=?");
                    pstmt.setLong(1, dcId);
                    rs = pstmt.executeQuery();
                    ArrayList<Object[]> routers = new ArrayList<Object[]>();
                    while (rs.next()) {
                        Object[] router = new Object[40];
                        router[0] = rs.getLong(1); // router id
                        router[1] = rs.getLong(2); // domain id
                        router[2] = rs.getLong(3); // account id
                        router[3] = rs.getString(4); // guest ip which becomes the gateway in network
                        router[4] = rs.getString(5); // domain name
                        router[5] = rs.getString(6); // dns1
                        router[6] = rs.getString(7); // dns2
                        router[7] = rs.getString(8); // vnet
                        routers.add(router);
                    }
                    rs.close();
                    pstmt.close();
                    
                    for (Object[] router : routers) {
                        String vnet = (String)router[7];
                        String reservationId = null;
                        String state = "Allocated";
                        if (vnet != null) {
                            reservationId = dcId + "-" + vnet;
                            state = "Implemented";
                        }
                        long virtualNetworkId = insertNetwork(conn, "VirtualNetwork" + router[0], "Virtual Network for " + router[0], "Guest", "Vlan", "vlan://" + vnet, (String)router[3], (String)dc[1], "Dhcp", 6, dcId, "GuestNetworkGuru", state, (Long)router[1], (Long)router[2], (String)router[5], (String)router[6], "Virtual", false, (String)router[4], true, reservationId);
                        pstmt = conn.prepareStatement("UPDATE domain_router SET network_id = ? wHERE id = ? ");
                        pstmt.setLong(1, virtualNetworkId);
                        pstmt.setLong(2, (Long)router[0]);
                        pstmt.executeUpdate();
                        pstmt.close();
                        s_logger.debug("Network inserted for " + router[0] + " id = " + virtualNetworkId);
                        
                        upgradeVirtualUserVms(conn, (Long)router[0], virtualNetworkId, (String)router[3], vnet);
                        upgradeDomR(conn, (Long)router[0], publicNetworkId, virtualNetworkId, controlNetworkId, "Advanced");
                    }
                    
                    upgradePublicUserIpAddress(conn, dcId, publicNetworkId, "VirtualNetwork");
                    
                    pstmt = conn.prepareStatement("SELECT id, vlan_id, vlan_gateway, vlan_netmask FROM vlan WHERE vlan_type='DirectAttached' AND data_center_id=?");
                    pstmt.setLong(1, dcId);
                    rs = pstmt.executeQuery();
                    while (rs.next()) {
                        long vlanId = rs.getLong(1);
                        String tag = rs.getString(2);
                        String gateway = rs.getString(3);
                        String netmask = rs.getString(4);
                        String cidr = NetUtils.getCidrFromGatewayAndNetmask(gateway, netmask);
                        long directNetworkId = insertNetwork(conn, "DirectNetwork" + vlanId, "Direct network created for " + vlanId, "Guest", "Vlan", "vlan://" + tag, gateway, cidr, "Dhcp", 7, dcId, "DirectNetworkGuru", "Setup", 1, 1, null, null, "Direct", true, (String)dc[2], true, null);
                        
                        pstmt = conn.prepareStatement("UPDATE vlan SET network_id=? WHERE id=?");
                        pstmt.setLong(1, directNetworkId);
                        pstmt.setLong(2, vlanId);
                        pstmt.executeUpdate();
                        pstmt.close();
                        
                        upgradeDirectUserIpAddress(conn, dcId, directNetworkId, "DirectAttached");
                    }
                    
                    upgradeSsvm(conn, dcId, publicNetworkId, storageNetworkId, controlNetworkId, "Advanced");
                    
                    pstmt = conn.prepareStatement("SELECT vm_instance.id FROM vm_instance WHERE removed IS NULL AND type='ConsoleProxy' AND data_center_id=?");
                    pstmt.setLong(1, dcId);
                    rs = pstmt.executeQuery();
                    while (rs.next()) {
                        upgradeConsoleProxy(conn, rs.getLong(1), publicNetworkId, mgmtNetworkId, controlNetworkId, "Advanced");
                    }
                    
                }
                
            }
        } catch (SQLException e) {
            throw new CloudRuntimeException("Can't update data center ", e);
        }
    }
    
    public void upgradePortForwardingRules(Connection conn) {
        try {
            PreparedStatement pstmt = conn.prepareStatement("SELECT id, public_ip_address, public_port, private_ip_address, private_port, protocol FROM ip_forwarding WHERE forwarding=1");
            ResultSet rs = pstmt.executeQuery();
            ArrayList<Object[]> rules = new ArrayList<Object[]>();
            while (rs.next()) {
                Object[] rule = new Object[10];
                rule[0] = rs.getLong(1); //rule id
                rule[1] = rs.getString(2); //rule public IP
                rule[2] = rs.getString(3); //rule public port
                rule[3] = rs.getString(4); //rule private Ip
                rule[4] = rs.getString(5); //rule private port
                rule[5] = rs.getString(6); //rule protocol
                rules.add(rule);
            }
            rs.close();
            pstmt.close();
            
            if (!rules.isEmpty()) {
                s_logger.debug("Found " + rules.size() + " port forwarding rules to upgrade");
                for (Object[] rule : rules) {
                    long id = (Long)rule[0];
                    String sourcePort = (String)rule[2];
                    String protocol = (String)rule[5];
                    String publicIp = (String)rule[1];
                    
                    pstmt = conn.prepareStatement("SELECT id, account_id, domain_id, network_id FROM user_ip_address WHERE public_ip_address=?");
                    pstmt.setString(1, publicIp);
                    rs = pstmt.executeQuery();
                    
                    if (!rs.next()) {
                        throw new CloudRuntimeException("Unable to find public IP address " + publicIp);
                    }
                    
                    int ipAddressId = rs.getInt(1);
                    long accountId = rs.getLong(2);
                    long domainId = rs.getLong(3);
                    long networkId = rs.getLong(4);
                    
                    rs.close();
                    pstmt.close();                       
                    
                    //update firewall_rules table
                    s_logger.debug("Updating firewall_rules table...");
                    pstmt = conn.prepareStatement("INSERT INTO firewall_rules VALUES (?,    ?,      ?,      ?,      'Active',        ?,     'PortForwarding',       ?,      ?,      ?,      ?,       now())");
                    pstmt.setLong(1, id);
                    pstmt.setInt(2, ipAddressId);
                    pstmt.setInt(3, Integer.valueOf(sourcePort));
                    pstmt.setInt(4, Integer.valueOf(sourcePort));
                    pstmt.setString(5, protocol);
                    pstmt.setLong(6, accountId);
                    pstmt.setLong(7, domainId);
                    pstmt.setLong(8, networkId);
                    pstmt.setString(9, UUID.randomUUID().toString());
                    pstmt.executeUpdate();
                    pstmt.close();
                    s_logger.debug("firewall_rules table is updated");
                    
                    //update port_forwarding_rules table
                    s_logger.debug("Updating port_forwarding_rules table...");
                    String privateIp = (String)rule[3];
                    pstmt = conn.prepareStatement("SELECT instance_id FROM nics where network_id=? AND ip4_address=?");
                    pstmt.setLong(1, networkId);
                    pstmt.setString(2, privateIp);
                    rs = pstmt.executeQuery();
                    
                    if (!rs.next()) {
                        throw new CloudRuntimeException("Unable to find vmId for private ip address " + privateIp + " for account id=" + accountId) ;
                    }
                        
                    long instanceId = rs.getLong(1);
                    s_logger.debug("Instance id is " + instanceId);
                    rs.close();
                    pstmt.close();
                        
                        
                    String privatePort = (String)rule[4];
                    pstmt = conn.prepareStatement("INSERT INTO port_forwarding_rules VALUES (?,    ?,      ?,      ?,       ?)");
                    pstmt.setLong(1, id);
                    pstmt.setLong(2, instanceId);
                    pstmt.setString(3, privateIp);
                    pstmt.setInt(4, Integer.valueOf(privatePort));
                    pstmt.setInt(5, Integer.valueOf(privatePort));
                    pstmt.executeUpdate();
                    pstmt.close();
                    s_logger.debug("port_forwarding_rules table is updated");
                }
            }
            
        } catch (SQLException e) {
            throw new CloudRuntimeException("Can't update port forwarding rules ", e);
        }
       
    }
    
    @Override
    public void performDataMigration(Connection conn) {
        upgradeDataCenter(conn);
        upgradeStoragePools(conn);
        upgradeInstanceGroups(conn);
        upgradePortForwardingRules(conn);
        
    }

    @Override
    public File[] getCleanupScripts() {
        return null;
        
//        String file = Script.findScript("", "db/schema-21to22-cleanup.sql");
//        if (file == null) {
//            throw new CloudRuntimeException("Unable to find the upgrade script, schema-21to22-cleanup.sql");
//        }
//        
//        return new File[] { new File(file) };
    }

    @Override
    public String[] getUpgradableVersionRange() {
        return new String[] { "2.1.7", "2.1.7" };
    }

    @Override
    public String getUpgradedVersion() {
        return "2.2.0";
    }
    
    @Override
    public boolean supportsRollingUpgrade() {
        return false;
    }
}
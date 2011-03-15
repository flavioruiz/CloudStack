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

package com.cloud.network.dao;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Date;
import java.util.List;

import javax.ejb.Local;

import org.apache.log4j.Logger;

import com.cloud.network.IPAddressVO;
import com.cloud.network.IpAddress.State;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.GenericSearchBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Func;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.Ip;

@Local(value = { IPAddressDao.class })
@DB
public class IPAddressDaoImpl extends GenericDaoBase<IPAddressVO, Long> implements IPAddressDao {
    private static final Logger s_logger = Logger.getLogger(IPAddressDaoImpl.class);

    protected final SearchBuilder<IPAddressVO> AllFieldsSearch;
    protected final SearchBuilder<IPAddressVO> VlanDbIdSearchUnallocated;
    protected final GenericSearchBuilder<IPAddressVO, Integer> AllIpCount;
    protected final GenericSearchBuilder<IPAddressVO, Integer> AllocatedIpCount;
    protected final GenericSearchBuilder<IPAddressVO, Integer> AllIpCountForDashboard;
    protected final GenericSearchBuilder<IPAddressVO, Integer> AllocatedIpCountForDashboard;
    
    
    // make it public for JUnit test
    public IPAddressDaoImpl() {
        AllFieldsSearch = createSearchBuilder();
        AllFieldsSearch.and("id", AllFieldsSearch.entity().getId(), Op.EQ);
        AllFieldsSearch.and("dataCenterId", AllFieldsSearch.entity().getDataCenterId(), Op.EQ);
        AllFieldsSearch.and("ipAddress", AllFieldsSearch.entity().getAddress(), Op.EQ);
        AllFieldsSearch.and("vlan", AllFieldsSearch.entity().getVlanId(), Op.EQ);
        AllFieldsSearch.and("accountId", AllFieldsSearch.entity().getAllocatedToAccountId(), Op.EQ);
        AllFieldsSearch.and("sourceNat", AllFieldsSearch.entity().isSourceNat(), Op.EQ);
        AllFieldsSearch.and("network", AllFieldsSearch.entity().getAssociatedWithNetworkId(), Op.EQ);
        AllFieldsSearch.and("associatedWithVmId", AllFieldsSearch.entity().getAssociatedWithVmId(), Op.EQ);
        AllFieldsSearch.and("oneToOneNat", AllFieldsSearch.entity().isOneToOneNat(), Op.EQ);
        AllFieldsSearch.done();

        VlanDbIdSearchUnallocated = createSearchBuilder();
        VlanDbIdSearchUnallocated.and("allocated", VlanDbIdSearchUnallocated.entity().getAllocatedTime(), Op.NULL);
        VlanDbIdSearchUnallocated.and("vlanDbId", VlanDbIdSearchUnallocated.entity().getVlanId(), Op.EQ);
        VlanDbIdSearchUnallocated.done();

        AllIpCount = createSearchBuilder(Integer.class);
        AllIpCount.select(null, Func.COUNT, AllIpCount.entity().getAddress());
        AllIpCount.and("dc", AllIpCount.entity().getDataCenterId(), Op.EQ);
        AllIpCount.and("vlan", AllIpCount.entity().getVlanId(), Op.EQ);
        AllIpCount.done();

        AllocatedIpCount = createSearchBuilder(Integer.class);
        AllocatedIpCount.select(null, Func.COUNT, AllocatedIpCount.entity().getAddress());
        AllocatedIpCount.and("dc", AllocatedIpCount.entity().getDataCenterId(), Op.EQ);
        AllocatedIpCount.and("vlan", AllocatedIpCount.entity().getVlanId(), Op.EQ);
        AllocatedIpCount.and("allocated", AllocatedIpCount.entity().getAllocatedTime(), Op.NNULL);
        AllocatedIpCount.done();
        
        AllIpCountForDashboard = createSearchBuilder(Integer.class);
        AllIpCountForDashboard.select(null, Func.COUNT, AllIpCountForDashboard.entity().getAddress());
        AllIpCountForDashboard.and("dc", AllIpCountForDashboard.entity().getDataCenterId(), Op.EQ);
        AllIpCountForDashboard.done();

        AllocatedIpCountForDashboard = createSearchBuilder(Integer.class);
        AllocatedIpCountForDashboard.select(null, Func.COUNT, AllocatedIpCountForDashboard.entity().getAddress());
        AllocatedIpCountForDashboard.and("dc", AllocatedIpCountForDashboard.entity().getDataCenterId(), Op.EQ);
        AllocatedIpCountForDashboard.and("allocated", AllocatedIpCountForDashboard.entity().getAllocatedTime(), Op.NNULL);
        AllocatedIpCountForDashboard.done();
    }

    @Override
    public boolean mark(long dcId, Ip ip) {
        SearchCriteria<IPAddressVO> sc = AllFieldsSearch.create();
        sc.setParameters("dataCenterId", dcId);
        sc.setParameters("ipAddress", ip);

        IPAddressVO vo = createForUpdate();
        vo.setAllocatedTime(new Date());
        vo.setState(State.Allocated);

        return update(vo, sc) >= 1;
    }

    /**
     * @deprecated This method is now deprecated because vlan has been
     *             added.  The actual method is now within NetworkManager.
     */
    @Deprecated
    public IPAddressVO assignIpAddress(long accountId, long domainId, long vlanDbId, boolean sourceNat) {
        Transaction txn = Transaction.currentTxn();
        txn.start();

        SearchCriteria<IPAddressVO> sc = VlanDbIdSearchUnallocated.create();
        sc.setParameters("vlanDbId", vlanDbId);
        
        Filter filter = new Filter(IPAddressVO.class, "vlanId", true, 0l, 1l);

        List<IPAddressVO> ips = this.lockRows(sc, filter, true);
        if (ips.size() == 0) {
            s_logger.info("Unable to get an ip address in " + vlanDbId);
            return null;
        }
        
        IPAddressVO ip = ips.get(0);

        ip.setAllocatedToAccountId(accountId);
        ip.setAllocatedTime(new Date());
        ip.setAllocatedInDomainId(domainId);
        ip.setSourceNat(sourceNat);
        ip.setState(State.Allocated);

        if (!update(ip.getId(), ip)) {
            throw new CloudRuntimeException("How can I lock the row but can't update it: " + ip.getAddress());
        }

        txn.commit();
        return ip;
    }

    @Override
    public void unassignIpAddress(long ipAddressId) {
        IPAddressVO address = createForUpdate();
        address.setAllocatedToAccountId(null);
        address.setAllocatedInDomainId(null);
        address.setAllocatedTime(null);
        address.setSourceNat(false);
        address.setOneToOneNat(false);
        address.setAssociatedWithVmId(null);
        address.setState(State.Free);
        address.setAssociatedWithNetworkId(null);
        update(ipAddressId, address);
    }

    @Override
    public List<IPAddressVO> listByAccount(long accountId) {
        SearchCriteria<IPAddressVO> sc = AllFieldsSearch.create();
        sc.setParameters("accountId", accountId);
        return listBy(sc);
    }
    
    @Override
    public List<IPAddressVO> listByVlanId(long vlanId) {
        SearchCriteria<IPAddressVO> sc = AllFieldsSearch.create();
        sc.setParameters("vlan", vlanId);
        return listBy(sc);
    }
    
    @Override
    public IPAddressVO findByAccountAndIp(long accountId, String ipAddress) {
        SearchCriteria<IPAddressVO> sc = AllFieldsSearch.create();
        sc.setParameters("accountId", accountId);
        sc.setParameters("ipAddress", ipAddress);
        return findOneBy(sc);
    }

    @Override
    public List<IPAddressVO> listByDcIdIpAddress(long dcId, String ipAddress) {
        SearchCriteria<IPAddressVO> sc = AllFieldsSearch.create();
        sc.setParameters("dataCenterId", dcId);
        sc.setParameters("ipAddress", ipAddress);
        return listBy(sc);
    }
    
    @Override
    public List<IPAddressVO> listByAssociatedNetwork(long networkId, Boolean isSourceNat) {
        SearchCriteria<IPAddressVO> sc = AllFieldsSearch.create();
        sc.setParameters("network", networkId);
        
        if (isSourceNat != null) {
            sc.setParameters("sourceNat", isSourceNat);
        }
        
        return listBy(sc);
    }
    
    @Override
    public IPAddressVO findByAssociatedVmId(long vmId) {
        SearchCriteria<IPAddressVO> sc = AllFieldsSearch.create();
        sc.setParameters("associatedWithVmId", vmId);
        
        return findOneBy(sc);
    }

    @Override
    public int countIPs(long dcId, long vlanId, boolean onlyCountAllocated) {
        SearchCriteria<Integer> sc = onlyCountAllocated ? AllocatedIpCount.create() : AllIpCount.create();
        sc.setParameters("dc", dcId);
        sc.setParameters("vlan", vlanId);

        return customSearch(sc, null).get(0);
    }

    @Override
    public int countIPsForDashboard(long dcId, boolean onlyCountAllocated) {
        SearchCriteria<Integer> sc = onlyCountAllocated ? AllocatedIpCountForDashboard.create() : AllIpCountForDashboard.create();
        sc.setParameters("dc", dcId);

        return customSearch(sc, null).get(0);
    }

    @Override
    @DB
    public int countIPs(long dcId, Long accountId, String vlanId, String vlanGateway, String vlanNetmask) {
        Transaction txn = Transaction.currentTxn();
        int ipCount = 0;
        try {
            String sql = "SELECT count(*) FROM user_ip_address u INNER JOIN vlan v on (u.vlan_db_id = v.id AND v.data_center_id = ? AND v.vlan_id = ? AND v.vlan_gateway = ? AND v.vlan_netmask = ? AND u.account_id = ?)";

            PreparedStatement pstmt = txn.prepareAutoCloseStatement(sql);
            pstmt.setLong(1, dcId);
            pstmt.setString(2, vlanId);
            pstmt.setString(3, vlanGateway);
            pstmt.setString(4, vlanNetmask);
            pstmt.setLong(5, accountId);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                ipCount = rs.getInt(1);
            }
        } catch (Exception e) {
            s_logger.warn("Exception counting IP addresses", e);
        }

        return ipCount;
    }

    @Override @DB
    public IPAddressVO markAsUnavailable(long ipAddressId) {
        SearchCriteria<IPAddressVO> sc = AllFieldsSearch.create();
        sc.setParameters("id", ipAddressId);
        
        IPAddressVO ip = createForUpdate();
        ip.setState(State.Releasing);
        if (update(ip, sc) != 1) {
            return null;
        }
        
        return findOneBy(sc);
    }
}
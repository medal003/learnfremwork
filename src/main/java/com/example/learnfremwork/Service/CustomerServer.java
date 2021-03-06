package com.example.learnfremwork.Service;

import com.example.learnfremwork.model.po.CustomerPo;

import java.util.List;

/**
 * @author medal <br/>
 * date: 2020/2/16/0016 17:54 <br/>
 * comment:
 */
public interface CustomerServer {
    List<CustomerPo> getAllCustomers();

    CustomerPo getOneCustomer(Long id);
}

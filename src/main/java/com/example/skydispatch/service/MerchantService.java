package com.example.skydispatch.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.skydispatch.entity.Merchant;
import com.example.skydispatch.mapper.MerchantMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 商户服务类
 * 提供商户的增删改查功能
 */
@Service
public class MerchantService {

    @Autowired
    private MerchantMapper merchantMapper;

    /**
     * 创建商户
     * @param name 商户名称
     * @param lat 纬度
     * @param lon 经度
     * @param address 详细地址
     * @return 创建成功的商户对象
     */
    public Merchant createMerchant(String name, Double lat, Double lon, String address) {
        Merchant merchant = new Merchant();
        merchant.setName(name);
        merchant.setLat(lat);
        merchant.setLon(lon);
        merchant.setAddress(address);
        merchantMapper.insert(merchant);
        return merchant;
    }

    /**
     * 根据ID获取商户信息
     */
    public Merchant getMerchant(Long id) {
        return merchantMapper.selectById(id);
    }

    /**
     * 获取所有商户列表
     */
    public List<Merchant> getAllMerchants() {
        return merchantMapper.selectList(new QueryWrapper<>());
    }
}

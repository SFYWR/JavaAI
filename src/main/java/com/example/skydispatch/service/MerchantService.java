package com.example.skydispatch.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.skydispatch.entity.Merchant;
import com.example.skydispatch.mapper.MerchantMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MerchantService {

    @Autowired
    private MerchantMapper merchantMapper;

    public Merchant createMerchant(String name, Double lat, Double lon, String address) {
        Merchant merchant = new Merchant();
        merchant.setName(name);
        merchant.setLat(lat);
        merchant.setLon(lon);
        merchant.setAddress(address);
        merchantMapper.insert(merchant);
        return merchant;
    }

    public Merchant getMerchant(Long id) {
        return merchantMapper.selectById(id);
    }

    public List<Merchant> getAllMerchants() {
        return merchantMapper.selectList(new QueryWrapper<>());
    }
}

package com.example.skydispatch.controller;

import com.example.skydispatch.entity.Merchant;
import com.example.skydispatch.service.MerchantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 商户管理接口
 * 提供商户的创建和查询功能
 */
@RestController
@RequestMapping("/api/merchants")
public class MerchantController {

    @Autowired
    private MerchantService merchantService;

    /**
     * 创建新商户
     * @param name 商户名称
     * @param lat 纬度
     * @param lon 经度
     * @param address 地址
     * @return 创建的商户对象
     */
    @PostMapping
    public ResponseEntity<Merchant> createMerchant(@RequestParam String name,
                                                   @RequestParam Double lat,
                                                   @RequestParam Double lon,
                                                   @RequestParam String address) {
        return ResponseEntity.ok(merchantService.createMerchant(name, lat, lon, address));
    }

    /**
     * 获取商户详情
     * @param id 商户ID
     * @return 商户对象
     */
    @GetMapping("/{id}")
    public ResponseEntity<Merchant> getMerchant(@PathVariable Long id) {
        Merchant merchant = merchantService.getMerchant(id);
        if (merchant != null) {
            return ResponseEntity.ok(merchant);
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 获取所有商户列表
     * @return 商户列表
     */
    @GetMapping
    public ResponseEntity<List<Merchant>> getAllMerchants() {
        return ResponseEntity.ok(merchantService.getAllMerchants());
    }
}

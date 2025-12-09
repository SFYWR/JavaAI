package com.example.skydispatch.controller;

import com.example.skydispatch.entity.Merchant;
import com.example.skydispatch.service.MerchantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/merchants")
public class MerchantController {

    @Autowired
    private MerchantService merchantService;

    @PostMapping
    public ResponseEntity<Merchant> createMerchant(@RequestParam String name,
                                                   @RequestParam Double lat,
                                                   @RequestParam Double lon,
                                                   @RequestParam String address) {
        return ResponseEntity.ok(merchantService.createMerchant(name, lat, lon, address));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Merchant> getMerchant(@PathVariable Long id) {
        Merchant merchant = merchantService.getMerchant(id);
        if (merchant != null) {
            return ResponseEntity.ok(merchant);
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping
    public ResponseEntity<List<Merchant>> getAllMerchants() {
        return ResponseEntity.ok(merchantService.getAllMerchants());
    }
}

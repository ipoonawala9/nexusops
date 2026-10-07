package com.nexusops.crm.web;

import com.nexusops.crm.CustomerRow;
import com.nexusops.crm.CustomerService;
import com.nexusops.crm.CustomerSummary;
import com.nexusops.shared.web.PageResponse;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/crm/customers")
class CustomerController {

    private final CustomerService customers;

    CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('crm.customer.read') and hasAuthority('directory.party.read')")
    PageResponse<CustomerRow> list(@RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return customers.list(q, page, size);
    }

    @GetMapping("/{partyId}")
    @PreAuthorize("hasAuthority('crm.customer.read') and hasAuthority('directory.party.read')")
    CustomerSummary summary(@PathVariable UUID partyId) {
        return customers.summary(partyId);
    }
}

package io.github.gonzalotguerrero.toshokan.members.controller;

import io.github.gonzalotguerrero.toshokan.members.dto.CreateMemberRequest;
import io.github.gonzalotguerrero.toshokan.members.dto.MemberDetails;
import io.github.gonzalotguerrero.toshokan.members.dto.UpdateMemberRequest;
import io.github.gonzalotguerrero.toshokan.members.model.MemberStatus;
import io.github.gonzalotguerrero.toshokan.members.service.MemberService;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.ConflictException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.InvalidRequestException;
import io.github.gonzalotguerrero.toshokan.shared.exceptions.NotFoundException;
import io.github.gonzalotguerrero.toshokan.shared.paging.PageResponse;
import io.github.gonzalotguerrero.toshokan.shared.web.ApiExceptionHandlerAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class MemberControllerTest {

    private static final MemberDetails ANA = new MemberDetails(UUID.randomUUID(), "CARD-0001", "Ana Pérez",
            "ana@example.com", null, MemberStatus.ACTIVE, Instant.parse("2026-01-10T09:00:00Z"));

    @Mock
    private MemberService service;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MemberController(service))
                .setControllerAdvice(new ApiExceptionHandlerAdvice())
                .build();
    }

    @Test
    void createsAMember() throws Exception {
        CreateMemberRequest request = new CreateMemberRequest("CARD-0001", "Ana Pérez", "ana@example.com", null);
        when(service.createMember(request)).thenReturn(ANA);

        mockMvc.perform(post("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cardNumber": "CARD-0001", "fullName": "Ana Pérez", "email": "ana@example.com"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/members/" + ANA.memberId()))
                .andExpect(jsonPath("$.memberId").value(ANA.memberId().toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void requiresACardNumberAndAName() throws Exception {
        mockMvc.perform(post("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardNumber\": \" \", \"email\": \"ana@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.cardNumber").exists())
                .andExpect(jsonPath("$.errors.fullName").exists());

        verifyNoInteractions(service);
    }

    @Test
    void answersARuleTheServiceRejectsWithBadRequest() throws Exception {
        when(service.createMember(any()))
                .thenThrow(new InvalidRequestException("A member needs an email or a phone number"));

        mockMvc.perform(post("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardNumber\": \"CARD-0001\", \"fullName\": \"Ana\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A member needs an email or a phone number"));
    }

    @Test
    void answersACardNumberInUseWithConflict() throws Exception {
        when(service.createMember(any())).thenThrow(new ConflictException("Card number CARD-0001 is already in use"));

        mockMvc.perform(post("/api/v1/members").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardNumber\": \"CARD-0001\", \"fullName\": \"Ana\", \"phone\": \"555\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void getsAMember() throws Exception {
        when(service.getMember(ANA.memberId())).thenReturn(ANA);

        mockMvc.perform(get("/api/v1/members/{memberId}", ANA.memberId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardNumber").value("CARD-0001"));
    }

    @Test
    void answersAnUnknownMemberWithNotFound() throws Exception {
        UUID memberId = UUID.randomUUID();
        when(service.getMember(memberId)).thenThrow(new NotFoundException("No member with id " + memberId));

        mockMvc.perform(get("/api/v1/members/{memberId}", memberId))
                .andExpect(status().isNotFound());
    }

    @Test
    void listsMembersWithDefaultPaging() throws Exception {
        when(service.listMembers(null, MemberService.DEFAULT_PAGE_SIZE, null))
                .thenReturn(new PageResponse<>(List.of(ANA), null));

        mockMvc.perform(get("/api/v1/members"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].fullName").value("Ana Pérez"));
    }

    @Test
    void passesTheCardNumberOnWhenLookingSomeoneUp() throws Exception {
        when(service.listMembers("CARD-0001", MemberService.DEFAULT_PAGE_SIZE, null))
                .thenReturn(new PageResponse<>(List.of(ANA), null));

        mockMvc.perform(get("/api/v1/members").param("cardNumber", "CARD-0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].memberId").value(ANA.memberId().toString()));
    }

    @Test
    void updatesAMember() throws Exception {
        UpdateMemberRequest request = new UpdateMemberRequest(null, null, "555-1234", null);
        when(service.updateMember(ANA.memberId(), request)).thenReturn(ANA);

        mockMvc.perform(patch("/api/v1/members/{memberId}", ANA.memberId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"phone\": \"555-1234\"}"))
                .andExpect(status().isOk());

        verify(service).updateMember(ANA.memberId(), request);
    }

    @Test
    void rejectsAStatusThatDoesNotExist() throws Exception {
        mockMvc.perform(patch("/api/v1/members/{memberId}", ANA.memberId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"BANNED\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void deletingAMemberDeactivatesThem() throws Exception {
        mockMvc.perform(delete("/api/v1/members/{memberId}", ANA.memberId()))
                .andExpect(status().isNoContent());

        verify(service).deactivateMember(ANA.memberId());
    }

    @Test
    void answersDeletingAnUnknownMemberWithNotFound() throws Exception {
        UUID memberId = UUID.randomUUID();
        doThrow(new NotFoundException("No member with id " + memberId)).when(service).deactivateMember(memberId);

        mockMvc.perform(delete("/api/v1/members/{memberId}", memberId))
                .andExpect(status().isNotFound());
    }
}

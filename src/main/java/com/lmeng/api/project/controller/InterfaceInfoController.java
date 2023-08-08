package com.lmeng.api.project.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.gson.Gson;
import com.lmeng.api.project.annotation.AuthCheck;
import com.lmeng.api.project.config.GatewayConfig;
import com.lmeng.api.project.exception.BusinessException;
import com.lmeng.api.project.exception.ThrowUtils;
import com.lmeng.api.project.service.UserService;
import com.lmeng.api.project.service.InterfaceInfoService;
import com.lmeng.apicommon.common.*;
import com.lmeng.apicommon.constant.CommonConstant;
import com.lmeng.apicommon.constant.UserConstant;
import com.lmeng.apicommon.model.dto.interfaceInfo.InterfaceInfoAddRequest;
import com.lmeng.apicommon.model.dto.interfaceInfo.InterfaceInfoInvokeRequest;
import com.lmeng.apicommon.model.dto.interfaceInfo.InterfaceInfoQueryRequest;
import com.lmeng.apicommon.model.dto.interfaceInfo.InterfaceInfoUpdateRequest;
import com.lmeng.apicommon.model.entity.InterfaceInfo;
import com.lmeng.apicommon.model.entity.User;
import com.lmeng.apicommon.model.enums.InterfaceStatusEnum;
import com.lmeng.nimbleclientsdk.client.NimbleApiClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

/**
 * 请求接口
 */
@RestController
@RequestMapping("/interfaceInfo")
@Slf4j
public class InterfaceInfoController {

    @Resource
    private InterfaceInfoService interfaceInfoService;

    @Resource
    private UserService userService;

    @Resource
    private GatewayConfig gatewayConfig;

    private final static Gson GSON = new Gson();

    // region 增删改查

    /**
     * 创建
     *
     * @param interfaceInfoAddRequest
     * @param request
     * @return
     */
    @PostMapping("/add")
    public BaseResponse<Long> addInterfaceInfo(@RequestBody InterfaceInfoAddRequest interfaceInfoAddRequest, HttpServletRequest request) {
        if (interfaceInfoAddRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        //1.校验请求参数，将请求参数复制到请求实体类
        InterfaceInfo interfaceInfo = new InterfaceInfo();
        BeanUtils.copyProperties(interfaceInfoAddRequest, interfaceInfo);
        //2.校验接口参数是否合法
        interfaceInfoService.validInterfaceInfo(interfaceInfo, true);
        //3.获取登录用户
        User loginUser = userService.getLoginUser(request);
        interfaceInfo.setUserId(loginUser.getId());
        //4.保存接口信息，如果失败抛出异常
        boolean result = interfaceInfoService.save(interfaceInfo);
        if(!result) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,"保存接口信息失败");
        }
        long newInterfaceInfoId = interfaceInfo.getId();
        return ResultUtils.success(newInterfaceInfoId);
    }

    /**
     * 删除接口
     *
     * @param deleteRequest
     * @param request
     * @return
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteInterfaceInfo(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        //1.获取当前用户
        User user = userService.getLoginUser(request);
        long id = deleteRequest.getId();
        //2.判断接口是否存在
        InterfaceInfo oldInterfaceInfo = interfaceInfoService.getById(id);
        ThrowUtils.throwIf(oldInterfaceInfo == null, ErrorCode.NOT_FOUND_ERROR);
        //3.仅本人或管理员可删除
        if (!oldInterfaceInfo.getUserId().equals(user.getId()) || !userService.isAdmin(request)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        boolean b = interfaceInfoService.removeById(id);
        return ResultUtils.success(b);
    }

    /**
     * 更新（仅管理员）
     *
     * @param interfaceInfoUpdateRequest
     * @return
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateInterfaceInfo(@RequestBody InterfaceInfoUpdateRequest interfaceInfoUpdateRequest) {
        if (interfaceInfoUpdateRequest == null || interfaceInfoUpdateRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        //1.将旧接口信息拷贝到新的接口类中
        InterfaceInfo interfaceInfo = new InterfaceInfo();
        BeanUtils.copyProperties(interfaceInfoUpdateRequest, interfaceInfo);
        //2.对接口的参数校验
        interfaceInfoService.validInterfaceInfo(interfaceInfo, false);
        //3.判断原来的接口是否存在
        long id = interfaceInfoUpdateRequest.getId();
        InterfaceInfo oldInterfaceInfo = interfaceInfoService.getById(id);
        //4.如果不存在抛出异常
        ThrowUtils.throwIf(oldInterfaceInfo == null, ErrorCode.NOT_FOUND_ERROR);
        //5.更新接口信息
        boolean result = interfaceInfoService.updateById(interfaceInfo);
        return ResultUtils.success(result);
    }

    /**
     * 根据 id 获取接口信息
     *
     * @param id
     * @return
     */
    @GetMapping("/get")
    public BaseResponse<InterfaceInfo> getInterfaceInfoById(long id) {
        if (id <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        InterfaceInfo interfaceInfo = interfaceInfoService.getById(id);
        return ResultUtils.success(interfaceInfo);
    }

    /**
     * 分页获取接口列表
     * @param interfaceInfoQueryRequest
     * @param request
     * @return
     */
    @GetMapping("/list/page")
    public BaseResponse<Page<InterfaceInfo>> listInterfaceInfoByPage(InterfaceInfoQueryRequest interfaceInfoQueryRequest, HttpServletRequest request) {
        if (interfaceInfoQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        //1.将接口参数拷贝到接口查询类
        InterfaceInfo interfaceInfoQuery = new InterfaceInfo();
        BeanUtils.copyProperties(interfaceInfoQueryRequest, interfaceInfoQuery);
        long current = interfaceInfoQueryRequest.getCurrent();
        long size = interfaceInfoQueryRequest.getPageSize();
        String sortField = interfaceInfoQueryRequest.getSortField();
        String sortOrder = interfaceInfoQueryRequest.getSortOrder();
        String description = interfaceInfoQuery.getDescription();
        // description 需支持模糊搜索
        interfaceInfoQuery.setDescription(null);
        // 限制爬虫
        if (size > 50) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        QueryWrapper<InterfaceInfo> queryWrapper = new QueryWrapper<>(interfaceInfoQuery);
        queryWrapper.like(StringUtils.isNotBlank(description), "description", description);
        queryWrapper.orderBy(StringUtils.isNotBlank(sortField),
                sortOrder.equals(CommonConstant.SORT_ORDER_ASC), sortField);
        Page<InterfaceInfo> interfaceInfoPage = interfaceInfoService.page(new Page<>(current, size), queryWrapper);
        return ResultUtils.success(interfaceInfoPage);
    }

    /**
     * 发布接口（管理员）
     *
     * @param idRequest
     * @return
     */
    @PostMapping("/online")
    @AuthCheck(mustRole = "admin")
    public BaseResponse<Boolean> onlineInterfaceInfoById(@RequestBody IdRequest idRequest, HttpServletRequest request) {
        //1.判断接口是否存在
        if(idRequest == null || idRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        long id = idRequest.getId();
        //2.判断接口是否存在
        InterfaceInfo interfaceInfo = interfaceInfoService.getById(id);
        if(interfaceInfo == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"接口不存在!");
        }

        //3.判断接口是否可以调用
        String method = interfaceInfo.getMethod();
        String url = interfaceInfo.getUrl();
        String requestParams = interfaceInfo.getRequestParams();
        //获取sdk客户端
        NimbleApiClient nimbleApiClient = interfaceInfoService.getNimbleApiClient(request);
        //设置网关地址
        nimbleApiClient.setGatewayHost(gatewayConfig.getHost());

        //4.测试调用
        String result = null;
        try {
            result = nimbleApiClient.invokeInterface(requestParams, url, method);
            if(StringUtils.isBlank(result)) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,"接口数据为空!");
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"接口验证错误!");
        }

        //5.可以调用，修改数据库
        InterfaceInfo newInterfaceInfo = new InterfaceInfo();
        newInterfaceInfo.setId(id);
        newInterfaceInfo.setStatus(InterfaceStatusEnum.ONLINE.getValue());
        //仅本人或者管理员才可以修改
        boolean res = interfaceInfoService.updateById(newInterfaceInfo);

        //6.返回
        return ResultUtils.success(res);
    }

    /**
     * 下线接口（管理员）
     *
     * @param idRequest
     * @return
     */
    @PostMapping("/offline")
    @AuthCheck(mustRole = "admin")
    public BaseResponse<Boolean> offlineInterfaceInfoById(@RequestBody IdRequest idRequest,HttpServletRequest request) {
        //1.判断接口是否存在
        if(idRequest == null || idRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        long id = idRequest.getId();
        //2.判断接口是否存在
        InterfaceInfo interfaceInfo = interfaceInfoService.getById(id);
        if(interfaceInfo == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"接口不存在!");
        }

        //3.修改数据库
        InterfaceInfo newInterfaceInfo = new InterfaceInfo();
        newInterfaceInfo.setId(id);
        newInterfaceInfo.setStatus(InterfaceStatusEnum.OFFLINE.getValue());
        //仅本人或者管理员才可以修改
        boolean res = interfaceInfoService.updateById(newInterfaceInfo);
        //5.返回
        return ResultUtils.success(res);
    }

    /**
     * 调用接口
     *
     * @param invokeRequest
     * @return
     */
    @PostMapping("/invoke")
    public BaseResponse<String> invokeInterfaceInfoById(@RequestBody InterfaceInfoInvokeRequest invokeRequest, HttpServletRequest request) {
        //1.判断接口是否存在
        if(invokeRequest == null || invokeRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        long id = invokeRequest.getId();
        //2.判断接口是否存在
        InterfaceInfo interfaceInfo = interfaceInfoService.getById(id);
        if(interfaceInfo == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,"接口不存在!");
        }
        //3.判断接口是否可以调用（上线状态）
        if(interfaceInfo.getStatus() == InterfaceStatusEnum.OFFLINE.getValue()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"接口已下线!");
        }
        //4.获取登录用户的签名
        String url = interfaceInfo.getUrl();
        String method = interfaceInfo.getMethod();
        String requestParams = invokeRequest.getRequestParams();

        //5.获取SDK客户端
        NimbleApiClient nimbleApiClient = interfaceInfoService.getNimbleApiClient(request);

        //6.设置网关地址
        nimbleApiClient.setGatewayHost(gatewayConfig.getHost());

        //7.调用接口
        String result = null;
        try {
            //执行调用接口
            result = nimbleApiClient.invokeInterface(requestParams, url, method);
            if(StringUtils.isBlank(result)) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,"接口数据为空!");
            }
        } catch (Exception e) {
             throw new BusinessException(ErrorCode.SYSTEM_ERROR, "接口验证失败");
        }
        //6.返回
        return ResultUtils.success(result);
    }


}

package com.question.admin.service.sysmgr.impl;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.question.admin.domain.entity.sysmgr.User;
import com.question.admin.domain.entity.sysmgr.UserRole;
import com.question.admin.mapper.sysmgr.UserMapper;
import com.question.admin.service.sysmgr.UserService;
import com.question.admin.config.shiro.ShiroKit;
import com.question.admin.config.shiro.security.JwtProperties;
import com.question.admin.config.shiro.security.JwtToken;
import com.question.admin.config.shiro.security.JwtUtil;
import com.question.admin.config.shiro.security.UserContext;
import com.question.admin.config.wx.SystemConfig;
import com.question.admin.constant.Constants;
import com.question.admin.constant.SecurityConsts;
import com.question.admin.constant.enumtype.YNFlagStatusEnum;
import com.question.admin.constant.sysmgr.UserStatusEnum;
import com.question.admin.domain.entity.sysmgr.LoginLog;
import com.question.admin.domain.vo.Result;
import com.question.admin.domain.vo.manager.sysmgr.UserPassword;
import com.question.admin.domain.vo.manager.sysmgr.UserRoleVo;
import com.question.admin.domain.vo.manager.sysmgr.UserVo;
import com.question.admin.domain.vo.wx.Code2SessionResponse;
import com.question.admin.service.sysmgr.LoginLogService;
import com.question.admin.utils.JSONUtil;
import com.question.admin.utils.JedisUtils;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.subject.Subject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import javax.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static com.question.admin.constant.SecurityConsts.HIDDEN_PASSWORD;
import static com.question.admin.utils.WxUtil.code2Session;

/**
 * <p>
 * 用户表 服务实现类
 * </p>
 *
 * @author zvc
 * @since 2018-12-28
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Autowired
    JwtProperties jwtProperties;

    @Autowired
    JedisUtils jedisUtils;

    @Autowired
    LoginLogService loginLogService;

   @Autowired
    private SystemConfig systemConfig;



    @Override
    public User findUserByAccount(String account) {
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.eq("account", account);
        wrapper.eq("yn_flag", YNFlagStatusEnum.VALID.getCode());

        List<User> userList = baseMapper.selectList(wrapper);
        return userList.size() > 0 ? userList.get(0) : null;
    }


    public User findUserByOpenId(String openId) {
        QueryWrapper<User> wrapper = new QueryWrapper<>();
        wrapper.eq("wx_open_id", openId);
        wrapper.eq("yn_flag", YNFlagStatusEnum.VALID.getCode());
        List<User> userList = baseMapper.selectList(wrapper);
        return userList.size() > 0 ? userList.get(0) : null;
    }

    @Override
    public Result wxUserLogin(String code, HttpServletResponse response) {

        //1 . code2session返回JSON数据
        String resultJson = code2Session(systemConfig.getWx(), code);
        //2 . 解析数据
        Code2SessionResponse code2SessionResponse = JSONUtil.toJavaObject(resultJson, Code2SessionResponse.class);
        if (!code2SessionResponse.getErrcode().equals("0"))
            throw new AuthenticationException("code2session失败 : " + code2SessionResponse.getErrmsg());
        else {
            //3 . 先从本地数据库中查找用户是否存在
            User userBean = this.findUserByOpenId(code2SessionResponse.getOpenid());
            if (userBean != null) {
                if ("0".equals(userBean.getStatus())) {
                    return new Result(false, "该账号已被锁定", null, Constants.PASSWORD_CHECK_INVALID);
                }
            }
            userBean = new User();
            String accout = org.apache.commons.lang3.StringUtils.defaultString(code2SessionResponse.getUnionid(), String.valueOf(ThreadLocalRandom.current().nextInt(999999)));
            userBean.setAccount(accout);
            userBean.setName(accout);
            userBean.setWxOpenId(code2SessionResponse.getOpenid());    //不存在就新建用户
            //4 . 更新sessionKey和 登陆时间
            userBean.setSessionKey(code2SessionResponse.getSession_key());
            userBean.setPassword(ShiroKit.md5(String.valueOf(ThreadLocalRandom.current().nextInt(999999)), SecurityConsts.LOGIN_SALT));
            userBean.setLastPwdModifiedTime(Date.from(Instant.now()));
            userBean.setStatus(UserStatusEnum.NORMAL.code());
            userBean.setYnFlag(YNFlagStatusEnum.VALID.getCode());
            userBean.setEditor("WX_SYS");
            userBean.setCreator("WX_SYS");
            Date currentDate = Date.from(Instant.now());
            userBean.setCreatedTime(currentDate);
            userBean.setModifiedTime(currentDate);
        //新增用户
            baseMapper.insert(userBean);
            //5 . JWT 返回自定义登陆态 Token
            String strToken = this.loginSuccess(userBean.getAccount(), response);

            Subject subject = SecurityUtils.getSubject();
            AuthenticationToken token = new JwtToken(strToken);
            subject.login(token);

            //登录成功
            return new Result(true, "登录成功", strToken, Constants.TOKEN_CHECK_SUCCESS);
        }
    }
    /**
     * 用户登录
     *
     * @param user
     * @return
     */
    @Override
    public Result login(UserVo user, HttpServletResponse response) {
        Assert.notNull(user.getUsername(), "用户名不能为空");
        Assert.notNull(user.getPassword(), "密码不能为空");

        User userBean = this.findUserByAccount(user.getUsername());

        if (userBean == null) {
            return new Result(false, "用户不存在", null, Constants.PASSWORD_CHECK_INVALID);
        }

        //ERP账号直接提示账号不存在
        if ("1".equals(userBean.getErpFlag())) {
            return new Result(false, "账号不存在", null, Constants.PASSWORD_CHECK_INVALID);
        }

        String encodePassword = ShiroKit.md5(user.getPassword(), SecurityConsts.LOGIN_SALT);
        if (!encodePassword.equals(userBean.getPassword())) {
            return new Result(false, "用户名或密码错误", null, Constants.PASSWORD_CHECK_INVALID);
        }

        //账号是否锁定
        if ("0".equals(userBean.getStatus())) {
            return new Result(false, "该账号已被锁定", null, Constants.PASSWORD_CHECK_INVALID);
        }

        String strToken= this.loginSuccess(userBean.getAccount(), response);

        Subject subject = SecurityUtils.getSubject();
        AuthenticationToken token= new JwtToken(strToken);
        subject.login(token);

        //登录成功
        return new Result(true, "登录成功", strToken, Constants.TOKEN_CHECK_SUCCESS);
    }

    /**
     * ERP登录
     * @return
     */
    @Override
    public Result loginErp(HttpServletResponse response) {

        //@Todo 待开发
//        User userBean = this.findUserByAccount("admin");
//        if (userBean == null || "0".equals(userBean.getErpFlag())) {
//            //ERP账号不在系统中，或者系统中标志是非ERP账号
//            return new Result(false, "用户未授权", null, Constants.PASSWORD_CHECK_INVALID);
//        }
//        //账号是否锁定
//        if ("0".equals(userBean.getStatus())) {
//            return new Result(false, "该账号已被锁定", null, Constants.PASSWORD_CHECK_INVALID);
//        }
        return new Result(true, "登录成功", null, Constants.TOKEN_CHECK_SUCCESS);
    }

    /**
     * 登录后更新缓存，生成token，设置响应头部信息
     *
     * @param account
     * @param response
     */
    private String loginSuccess(String account, HttpServletResponse response) {

        String currentTimeMillis = String.valueOf(System.currentTimeMillis());

        //生成token
        JSONObject json = new JSONObject();
        String token = JwtUtil.sign(account, currentTimeMillis);
        json.put("token", token);

        //更新RefreshToken缓存的时间戳
        String refreshTokenKey= SecurityConsts.PREFIX_SHIRO_REFRESH_TOKEN + account;
        jedisUtils.saveString(refreshTokenKey, currentTimeMillis, jwtProperties.getTokenExpireTime()*60);

        //记录登录日志
        LoginLog loginLog= new LoginLog();
        loginLog.setAccount(account);
        loginLog.setLoginTime(Date.from(Instant.now()));
        loginLog.setContent("登录成功");
        loginLog.setYnFlag(YNFlagStatusEnum.VALID.getCode());
        loginLog.setCreator(account);
        loginLog.setEditor(account);
        loginLog.setCreatedTime(loginLog.getLoginTime());
        loginLogService.save(loginLog);

        //写入header
        response.setHeader(SecurityConsts.REQUEST_AUTH_HEADER, token);
        response.setHeader("Access-Control-Expose-Headers", SecurityConsts.REQUEST_AUTH_HEADER);

        return token;
    }

    /**
     * 保存
     *
     * @param user
     * @return
     */
    @Override
    public Result persist(User user) {

        Date currentDate = Date.from(Instant.now());
        if (user.getId() == null) {
            User existUser = this.findUserByAccount(user.getAccount());
            if (existUser != null) {
                //账号已存在
                return new Result(false, "账号已经存在");
            } else {
                //保存密码
                if (!StringUtils.isEmpty(user.getPassword())) {
                    user.setPassword(ShiroKit.md5(user.getPassword(), SecurityConsts.LOGIN_SALT));
                }
                user.setLastPwdModifiedTime(Date.from(Instant.now()));
//                user.setStatus(UserStatusEnum.NORMAL.code());
                user.setYnFlag(YNFlagStatusEnum.VALID.getCode());
                user.setEditor(UserContext.getCurrentUser().getAccount());
                user.setCreator(UserContext.getCurrentUser().getAccount());
                user.setCreatedTime(currentDate);
                user.setModifiedTime(currentDate);
                //新增用户
                baseMapper.insert(user);
            }
        } else {
            User userBean = baseMapper.selectById(user.getId());
            if (user.getAccount().equals(userBean.getAccount())) {
                if (!user.getPassword().equals(HIDDEN_PASSWORD)) {
                    //修改密码
                    user.setPassword(ShiroKit.md5(user.getPassword(), SecurityConsts.LOGIN_SALT));
                    user.setLastPwdModifiedTime(Date.from(Instant.now()));
                } else {
                    user.setPassword(userBean.getPassword());
                    user.setLastPwdModifiedTime(userBean.getLastPwdModifiedTime());
                }
                user.setEditor(UserContext.getCurrentUser().getAccount());
                user.setModifiedTime(currentDate);
                //更新用户
                baseMapper.updateById(user);
            } else {
                return new Result(false, "账号不能修改", null, Constants.PARAMETERS_MISSING);
            }
        }

        return new Result(true, "修改成功", null, Constants.TOKEN_CHECK_SUCCESS);
    }

    @Override
    public Result findUserRole(Long userId) {
        List<Long> auths = baseMapper.selectRoleByUserId(userId);
        return new Result(true, null, auths, Constants.TOKEN_CHECK_SUCCESS);
    }

    /**
     * 保存用户角色
     *
     * @param userRole
     * @return
     */
    @Override
    public Result saveUserRoles(UserRoleVo userRole) {
        Date currentDate = Date.from(Instant.now());

        UserRole user = new UserRole();
        user.setUserId(userRole.getUserId());
        user.setModifiedTime(currentDate);
        baseMapper.deleteRoleByUserId(user);

        UserRole tempUserRole;
        List<UserRole> authList = new ArrayList<>();
        for (Long roleId : userRole.getRoleIds()) {
            tempUserRole = new UserRole(userRole.getUserId(), roleId);
            tempUserRole.setYnFlag("1");
            tempUserRole.setEditor(UserContext.getCurrentUser().getAccount());
            tempUserRole.setCreator(UserContext.getCurrentUser().getAccount());
            tempUserRole.setCreatedTime(currentDate);
            tempUserRole.setModifiedTime(currentDate);
            authList.add(tempUserRole);
        }
        baseMapper.batchInsertUserRole(authList);

        return new Result(true, null, null, Constants.TOKEN_CHECK_SUCCESS);
    }

    /**
     * 修改密码
     *
     * @param userPassword
     * @return
     */
    @Override
    public Result editPassWord(UserPassword userPassword) {
        if (!StringUtils.isEmpty(userPassword.getPassword()) && !StringUtils.isEmpty(userPassword.getNewPassword())) {

            User user = this.findUserByAccount(UserContext.getCurrentUser().getAccount());

            String encodeNewPassword = ShiroKit.md5(userPassword.getPassword(), SecurityConsts.LOGIN_SALT);
            if (YNFlagStatusEnum.FAIL.getCode().equals(user.getErpFlag())) {
                if (user.getPassword().equals(encodeNewPassword)) {
                    User entity = new User();
                    entity.setPassword(ShiroKit.md5(userPassword.getNewPassword(), SecurityConsts.LOGIN_SALT));
                    entity.setEditor(UserContext.getCurrentUser().getAccount());

                    entity.setEditor(UserContext.getCurrentUser().getAccount());
                    entity.setModifiedTime(Date.from(Instant.now()));

                    QueryWrapper<User> wrapper = new QueryWrapper<>();
                    wrapper.eq("yn_flag", "1");
                    wrapper.eq("account", user.getAccount());

                    baseMapper.update(entity, wrapper);

                    return new Result(true, "修改成功", null, Constants.TOKEN_CHECK_SUCCESS);
                } else {
                    //原始密码错误
                    return new Result(false, "原密码错误", null, Constants.PASSWORD_CHECK_INVALID);
                }
            } else {
                return new Result(false, "参数不完整", null, Constants.PARAMETERS_MISSING);
            }
        }
        return new Result(false, "参数不完整", null, Constants.PARAMETERS_MISSING);
    }

}
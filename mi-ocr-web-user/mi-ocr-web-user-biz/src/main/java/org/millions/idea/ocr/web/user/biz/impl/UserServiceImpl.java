/***
 * @pName mi-ocr-web-user
 * @name UserServiceImpl
 * @user HongWei
 * @date 2018/6/22
 * @desc
 */
package org.millions.idea.ocr.web.user.biz.impl;

import org.millions.idea.ocr.web.common.entity.exception.MessageException;
import org.millions.idea.ocr.web.common.utility.encrypt.Md5Util;
import org.millions.idea.ocr.web.common.utility.json.JsonUtil;
import org.millions.idea.ocr.web.common.utility.utils.PropertyUtil;
import org.millions.idea.ocr.web.user.agent.order.IWalletAgentService;
import org.millions.idea.ocr.web.user.biz.IUserService;
import org.millions.idea.ocr.web.user.entity.db.Users;
import org.millions.idea.ocr.web.user.entity.ext.UserEntity;
import org.millions.idea.ocr.web.user.repository.mapper.IUserMapperRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class UserServiceImpl implements IUserService {
    private final IUserMapperRepository userMapperRepository;
    private final RedisTemplate redisTemplate;

    @Autowired
    private IWalletAgentService walletAgentService;
    @Autowired
    public UserServiceImpl(IUserMapperRepository userMapperRepository,  RedisTemplate redisTemplate) {
        this.userMapperRepository = userMapperRepository;
        this.redisTemplate = redisTemplate;
    }


    /**
     * Get user information by uid
     *
     * @param uid
     * @return
     */
    @Override
    public Users getUser(Integer uid) {
        return userMapperRepository.query(uid);
    }

    /**
     * Login
     *
     * @param uname
     * @param pwd
     * @return
     */
    @Override
    public String directLogin(String uname, String pwd) {
        /*
            登录流程：
                1、获取加密后的密码
                2、查询数据库
                3、存入缓存服务器并返回key
         */
        String newPwd = Md5Util.getMd5(uname + pwd);
        Users user = userMapperRepository.login(uname, newPwd);
        if(user == null) throw new MessageException("用户名或密码错误");

        UserEntity userEntity = new UserEntity();
        PropertyUtil.clone(user, userEntity);

        String key = UUID.randomUUID().toString();
        userEntity.setToken(key);
        userEntity.setWallet(walletAgentService.get(userEntity.getUid()));
        redisTemplate.opsForValue().set(key, JsonUtil.getJson(userEntity), 30, TimeUnit.MINUTES);
        redisTemplate.opsForValue().set("stock_" + userEntity.getUid(), userEntity.getWallet().getBalance().toString(), 1, TimeUnit.DAYS);
        return key;
    }

    /**
     * 查询余额
     *
     * @param token
     * @return
     */
    @Override
    public BigDecimal getBalance(String token) {
        Object json = redisTemplate.opsForValue().get(token);
        if(json == null) throw new MessageException("请重新登录");
        UserEntity userEntity = JsonUtil.getModel(String.valueOf(json), UserEntity.class);
        return userEntity.getWallet().getBalance();
    }

    /**
     * 通过web渠道登录账户
     *
     * @param username
     * @param password
     * @param vcode
     * @return
     */
    @Override
    public Integer webLogin(String username, String password, String vcode) throws MessageException{
        /*
            1、判断验证码是否正确
            2、判断用户名密码是否正确
         */

        // 1、判断验证码是否正确

        // 2、判断用户名密码是否正确
        String nPassword = Md5Util.getMd5(username + password);
        Users user = userMapperRepository.login(username, nPassword);
        if(user == null) throw new MessageException("用户名或密码错误");
        String key = Md5Util.getMd5(user.getUid() + user.getUserName());
        redisTemplate.opsForValue().set(key, JsonUtil.getJson(user), 30, TimeUnit.MINUTES);
        return user.getUid();
    }

}

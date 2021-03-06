/***
 * @pName mi-ocr-web-user
 * @name UserServiceImpl
 * @user HongWei
 * @date 2018/6/22
 * @desc
 */
package org.millions.idea.ocr.web.user.biz.impl;

import org.millions.idea.ocr.web.common.entity.exception.MessageException;
import org.millions.idea.ocr.web.common.utility.date.DateUtil;
import org.millions.idea.ocr.web.common.utility.encrypt.Md5Util;
import org.millions.idea.ocr.web.common.utility.json.JsonUtil;
import org.millions.idea.ocr.web.common.utility.utils.PropertyUtil;
import org.millions.idea.ocr.web.common.utility.utils.RequestUtil;
import org.millions.idea.ocr.web.user.agent.order.IWalletAgentService;
import org.millions.idea.ocr.web.user.biz.IUserService;
import org.millions.idea.ocr.web.user.entity.agent.UserDetailEntity;
import org.millions.idea.ocr.web.user.entity.agent.WalletEntity;
import org.millions.idea.ocr.web.user.entity.db.Users;
import org.millions.idea.ocr.web.user.entity.ext.UserEntity;
import org.millions.idea.ocr.web.user.repository.mapper.IUserMapperRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class UserServiceImpl implements IUserService {
    private Logger logger = LoggerFactory.getLogger(getClass());
    private final IUserMapperRepository userMapperRepository;
    private final RedisTemplate redisTemplate;
    private final IWalletAgentService walletAgentService;

    @Autowired
    public UserServiceImpl(IUserMapperRepository userMapperRepository, RedisTemplate redisTemplate, IWalletAgentService walletAgentService) {
        this.userMapperRepository = userMapperRepository;
        this.redisTemplate = redisTemplate;
        this.walletAgentService = walletAgentService;
    }


    /**
     * Get user information by uid
     *
     * @param uid
     * @return
     */
    @Override
    public Users getUser(Integer uid) {
        return userMapperRepository.select(uid);
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
     * 根据username查询用户信息
     *
     * @param username
     * @return
     */
    @Override
    public UserDetailEntity webLogin(String username, String lastLoginIp) throws MessageException{
        // 查询用户基础资料
        Users user =  userMapperRepository.selectUserByUsername(username);
        if(user == null) throw new MessageException("用户不存在");


        // 更新归属地
        Timestamp lastActiveTime = DateUtil.convert(new Timestamp(System.currentTimeMillis()));
        if (lastLoginIp == null) lastLoginIp = "0.0.0.0";

        String area = RequestUtil.getCity(lastLoginIp);

        int result = userMapperRepository.updateActive(username,lastActiveTime,lastLoginIp,area);
        logger.info("UserService_updateActive" + result);

        // 查询用户钱包信息
        WalletEntity wallet = walletAgentService.get(user.getUid());
        UserDetailEntity detail = new UserDetailEntity();
        PropertyUtil.clone(user, detail);
        PropertyUtil.clone(wallet, detail);

        return detail;
    }

    /**
     * 添加新用户
     *
     * @param user
     * @return
     */
    @Override
    public boolean addUser(Users user) {
        // 插入用户表
        if(user.getEmail().indexOf("@") == 0) return false;
        user.setPassword(Md5Util.getMd5(user.getUserName() + user.getPassword()));
        int addUserCount = userMapperRepository.insert(user);
        logger.debug("UserServiceImpl_addUser_userMapperRepository_insert" + addUserCount + ":" + user.getUid());
        if(addUserCount <= 0 || user.getUid() == null) return false;
        // 插入钱包表
        Integer walletId = walletAgentService.addNewWallet(user.getUid());
        logger.debug("UserServiceImpl_addUser_walletAgentService_add" + walletId);
        if (walletId == null || walletId <= 0){
            // TODO 下一版考虑TCC补偿事务或者基于MQ的分布式事务
            userMapperRepository.delete(user.getUid());
            return false;
        }
        return user.getUid() != null && user.getUid() > 0 && walletId > 0;
    }

}
